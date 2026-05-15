# Runbook operacional

Escenarios de fallo más frecuentes, diagnóstico y acciones correctivas.

---

## Diagnóstico general

```sql
-- Estado de registros
SELECT status, count(*) FROM data.raw_data GROUP BY status ORDER BY count DESC;

-- Últimos registros en error
SELECT process_id, idempotency_id, template_name, retry_count, error_message, updated_at
FROM data.raw_data
WHERE status IN ('ERROR', 'FAILED')
ORDER BY updated_at DESC LIMIT 20;

-- Registros PROCESSING colgados
SELECT process_id, idempotency_id, template_name, updated_at,
       EXTRACT(EPOCH FROM (now() - updated_at)) / 60 AS minutes_stuck
FROM data.raw_data
WHERE status = 'PROCESSING'
  AND updated_at < now() - INTERVAL '30 minutes';
```

---

## Escenario 1 — Acumulación de registros PENDING

**Síntoma:** creciente número de registros en `PENDING` que no se procesan.

**Causas posibles:**
- El cron no está activo (variable de cron mal configurada)
- El servicio no está corriendo

**Diagnóstico:**
```bash
kubectl logs -n <namespace> deploy/document-generator-api --tail=100 | grep -E "Cron|scheduler|PENDING"
kubectl get configmap document-generator-api-config -o yaml | grep cron
```

**Acción:** revisar el log de arranque y verificar que el scheduler disparó. El scheduler corre automáticamente según el cron; no hay endpoint de trigger manual en esta versión.

---

## Escenario 2 — Registros PROCESSING colgados

**Síntoma:** registros en `status = PROCESSING` que llevan más de 30 minutos sin avanzar.

**Causa:** el servicio se reinició mientras el scheduler procesaba un registro. Los registros quedan en `PROCESSING` sin cleanup.

**Acción correctiva (automática):** `recoverStuckProcessing()` los resetea a `PENDING` al inicio de cada ciclo del scheduler. No requiere intervención manual si el servicio está corriendo.

**Acción correctiva (manual — si el servicio no levanta):**
```sql
UPDATE data.raw_data
SET status = 'PENDING', updated_at = now()
WHERE status = 'PROCESSING'
  AND updated_at < now() - INTERVAL '30 minutes';
```

---

## Escenario 3 — Registros en FAILED (agotaron reintentos)

**Síntoma:** registros en `status = FAILED` que no se procesarán automáticamente.

**Diagnóstico:**
```sql
SELECT process_id, idempotency_id, template_name, error_message
FROM data.raw_data WHERE status = 'FAILED'
ORDER BY updated_at DESC;
```

**Acción correctiva — reactivar un registro:**
```sql
UPDATE data.raw_data
SET status = 'PENDING', retry_count = 0, error_message = NULL, updated_at = now()
WHERE process_id = <process_id>;
```

**Acción correctiva — reactivar por template:**
```sql
UPDATE data.raw_data
SET status = 'PENDING', retry_count = 0, error_message = NULL, updated_at = now()
WHERE status = 'FAILED' AND template_name = 'compra-y-gana-empresas-prueba-002';
```

---

## Escenario 4 — Template no encontrado

**Síntoma:** registros fallando con `TemplateNotFoundException: <templateName>`.

**Diagnóstico:**
```sql
-- Verificar que existe en el catálogo
SELECT name, gcs_key, status FROM data.document_templates WHERE name = '<templateName>';
```

```bash
# Verificar que el archivo existe en GCS
curl "http://localhost:4443/storage/v1/b/document-generator-bucket/o/templates%2F<templateName>.pdf"
```

**Acción correctiva:**
1. Si no está en el catálogo → subir el template via `POST /v1/templates`
2. Si el `gcs_key` es incorrecto → `UPDATE data.document_templates SET gcs_key = '...' WHERE name = '...'`
3. Si el archivo no está en GCS → subir el PDF (ver [gcs-templates.md](gcs-templates.md))
4. Reactivar los registros FAILED del template afectado

---

## Escenario 5 — Error de conexión a GCS

**Síntoma:** registros fallando con `StorageException` o `TemplateDownloadException`.

**Diagnóstico:**
```bash
kubectl logs -n <namespace> deploy/document-generator-api --tail=200 | grep -E "GCS|StorageException"
kubectl exec -n <namespace> deploy/document-generator-api -- \
  curl -s https://storage.googleapis.com/storage/v1/b/document-generator-bucket
```

**Acción correctiva:**
1. Verificar credenciales GCS (Workload Identity o Service Account)
2. Verificar que el bucket `document-generator-bucket` existe y el SA tiene `storage.objectAdmin`
3. Una vez resuelta la conectividad, reactivar los registros fallidos

---

## Escenario 6 — Consumer Kafka no procesa eventos

**Síntoma:** eventos publicados al topic `document.generation.requested` no se persisten en `raw_data`.

**Diagnóstico:**
```bash
kafka-consumer-groups.sh --bootstrap-server <kafka:9092> \
  --describe --group document-generator-api

kafka-console-consumer.sh --bootstrap-server <kafka:9092> \
  --topic document.generation.requested \
  --from-beginning --max-messages 5
```

**Acción correctiva:**
1. Verificar que el servicio esté corriendo (health check)
2. Si hay lag acumulado, el consumer lo procesará al reiniciar (offset guardado)
3. Si está en error, revisar logs y reiniciar el servicio

---

## Escenario 7 — Problema con Flyway al arrancar

**Síntoma:** `FlywayException: Found non-empty schema(s) "data" but no schema history table`.

**Causa:** el schema `data` tiene tablas pero no tiene `flyway_schema_history` (esquema previo sin Flyway, o migración parcial).

**Acción correctiva (local):**
```bash
docker exec -it document-generator-api-postgres psql -U user -d files \
  -c "DROP SCHEMA data CASCADE; CREATE SCHEMA data;"
```

Luego reiniciar la app: Flyway ejecuta V1 y V2 desde cero.

---

## Escenario 8 — Registros GENERATED que no pasan a PUBLISHED

**Síntoma:** registros en `status = GENERATED` que no pasan a `PUBLISHED`.

**Causa:** error de publicación en Kafka o el scheduler falló entre TX3 (markGenerated) y TX4 (markPublished).

**Diagnóstico:**
```sql
SELECT count(*) FROM data.raw_data WHERE status = 'GENERATED';

SELECT process_id, template_name, processed_at FROM data.raw_data
WHERE status = 'GENERATED' ORDER BY processed_at ASC LIMIT 10;
```

**Acción correctiva:**
Los registros `GENERATED` son recogidos por el scheduler en el próximo ciclo (el claim de `PENDING`/`ERROR` es el único paso de selección; `GENERATED` se publica en el mismo ciclo en que fueron generados). Si persisten, verificar conectividad con Kafka.


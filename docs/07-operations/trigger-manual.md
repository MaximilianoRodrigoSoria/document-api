# Trigger manual del Job

El Job de generación de documentos se ejecuta automáticamente mediante un scheduler (`@Scheduled`). En ciertos casos es necesario dispararlo manualmente: fuera del horario programado, para reprocesar registros corregidos, o durante testing y debugging.

---

## Endpoint

```
POST /batch-document-generator/v1/jobs/document-generation/run
```

No requiere body. No requiere parámetros.

---

## Comportamiento

Al disparar el endpoint:

1. Se consulta cuántos registros serán procesados (`PENDING` + `ERROR` con reintentos disponibles).
2. Se lanza el Job con un parámetro `timestamp = System.currentTimeMillis()`.
3. El endpoint retorna **inmediatamente** con `202 Accepted` — el Job corre de forma asíncrona.
4. El Job ejecuta el Step 1 (generación) y luego el Step 2 (publicación).

El parámetro `timestamp` garantiza que cada ejecución manual sea tratada como una nueva `JobInstance` por Spring Batch, incluso si el Job ya fue ejecutado anteriormente con el mismo conjunto de parámetros.

---

## Respuesta exitosa — 202 Accepted

```json
{
  "jobExecutionId":   42,
  "jobName":          "documentGenerationJob",
  "status":           "STARTED",
  "recordsToProcess": 1250
}
```

| Campo | Descripción |
|-------|-------------|
| `jobExecutionId` | ID de la ejecución en las tablas de metadata de Spring Batch |
| `jobName` | Nombre del Job lanzado |
| `status` | Estado inicial al momento del retorno (típicamente `STARTED`) |
| `recordsToProcess` | Registros que el Job intentará procesar (conteo previo al lanzamiento) |

---

## Respuesta de error — 500 Internal Server Error

```json
{
  "error": "Failed to launch job: <mensaje de error>"
}
```

Ocurre si el `JobLauncher` no puede iniciar el Job (ej. problema de conectividad con la base de datos de metadata de Spring Batch).

---

## Cómo dispararlo

### Desde curl (línea de comandos)

```bash
# Local
curl -X POST http://localhost:8080/batch-document-generator/v1/jobs/document-generation/run

# Con output formateado
curl -s -X POST http://localhost:8080/batch-document-generator/v1/jobs/document-generation/run \
  | python3 -m json.tool
```

### Desde Swagger UI

1. Abrir `http://localhost:8080/batch-document-generator/swagger-ui.html`
2. Sección **Jobs**
3. `POST /v1/jobs/document-generation/run` → Execute

### Desde kubectl (staging/producción)

```bash
# Obtener el host del servicio
kubectl get svc -n <namespace> batch-document-generator

# Disparar el Job desde un pod dentro del cluster
kubectl exec -n <namespace> deploy/batch-document-generator -- \
  curl -s -X POST http://localhost:8080/batch-document-generator/v1/jobs/document-generation/run
```

---

## Seguimiento de la ejecución

### Por logs

```bash
# Local
./gradlew bootRun --args='--spring.profiles.active=local' 2>&1 | grep -E "Job|Step|launched|completed"

# Kubernetes
kubectl logs -n <namespace> deploy/batch-document-generator -f | grep -E "Job|Step|executionId"
```

Los logs incluyen el `jobExecutionId` retornado por el endpoint, lo que permite correlacionar la ejecución.

### Por base de datos

```sql
-- Estado de la ejecución
SELECT job_execution_id, status, start_time, end_time, exit_code, exit_message
FROM batch.batch_job_execution
WHERE job_execution_id = <jobExecutionId>;

-- Detalle por step
SELECT step_name, status, read_count, write_count, skip_count, start_time, end_time
FROM batch.batch_step_execution
WHERE job_execution_id = <jobExecutionId>;
```

---

## Casos de uso frecuentes

| Situación | Acción |
|-----------|--------|
| Hay registros PENDING acumulados y no quiero esperar al próximo cron | Disparar el Job manualmente |
| Se corrigió un template y se quiere reprocesar los FAILED | Resetear a PENDING + disparar el Job |
| Testing en desarrollo local después de insertar registros de prueba | Disparar el Job manualmente |
| Verificar que el sistema funciona después de un deployment | Disparar el Job y verificar logs |
| El cron no está configurado en el ambiente de staging | Disparar el Job manualmente para cada prueba |

---

## Notas importantes

- El endpoint **no bloquea** hasta que el Job termine. Retorna `202` apenas el Job empieza.
- Si hay un Job ya corriendo (lanzado por el scheduler o por otra llamada manual), Spring Batch puede rechazar el nuevo lanzamiento dependiendo de la configuración del `TaskExecutor`. Verificar los logs ante un posible conflicto.
- El `recordsToProcess` en la respuesta es un snapshot previo al lanzamiento. El número real procesado puede diferir si hay registros nuevos que llegan mientras el Job corre.

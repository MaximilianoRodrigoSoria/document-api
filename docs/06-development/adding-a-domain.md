# Agregar un nuevo dominio

Esta guía cubre los pasos necesarios para incorporar un nuevo dominio funcional al servicio (ej. `SEGUROS`, `CREDITOS`). Un dominio implica un nuevo tipo de documento con su propio template JasperReports.

---

## Checklist completo

- [ ] 1. Agregar el valor al enum `DocumentDomain`
- [ ] 2. Compilar el template JasperReports (`.jrxml` → `.jasper`)
- [ ] 3. Subir el template a GCS
- [ ] 4. Registrar el template en `batch.document_templates`
- [ ] 5. Verificar que el producer envía `templateName` y `domain` correctos
- [ ] 6. Ejecutar un test de humo end-to-end

---

## Paso 1 — Agregar el valor al enum `DocumentDomain`

**Archivo:** `src/main/java/cl/tenpo/batch/document/generator/model/enums/DocumentDomain.java`

```java
public enum DocumentDomain {
    MISIONES,
    LOANS,
    SEGUROS   // <- agregar aquí
}
```

El valor del enum determina la subcarpeta de GCS donde se almacenan los documentos generados. Por ejemplo, `SEGUROS` → `documents/seguros/`.

---

## Paso 2 — Compilar el template JasperReports

El template debe estar en formato `.jasper` (binario compilado), no en formato `.jrxml` (fuente XML).

```bash
# Opción A: desde Jaspersoft Studio (recomendado para templates complejos)
# File > Compile Report → genera el .jasper en el mismo directorio

# Opción B: vía maven/gradle con el plugin de JasperReports
# Opción C: programáticamente con JasperCompileManager
```

El archivo `.jasper` es el que se sube a GCS y se descarga en runtime. El `.jrxml` es fuente y puede guardarse en el repositorio para control de versiones.

**Convención de nombre:** `{templateName}-{version}.jasper`
Ejemplo: `seguros-contrato-v1-1.0.0.jasper`

---

## Paso 3 — Subir el template a GCS

```bash
# Con gsutil (producción/staging)
gsutil cp seguros-contrato-v1-1.0.0.jasper \
  gs://batch-document-generator/templates/seguros/seguros-contrato-v1-1.0.0.jasper

# Con curl sobre fake-gcs-server (local)
curl -X POST \
  "http://localhost:4443/upload/storage/v1/b/batch-document-generator/o?uploadType=media&name=templates/seguros/seguros-contrato-v1-1.0.0.jasper" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @seguros-contrato-v1-1.0.0.jasper
```

**Convención de path GCS:**
```
templates/{domain_lowercase}/{templateName}-{version}.jasper
```
Ejemplo: `templates/seguros/seguros-contrato-v1-1.0.0.jasper`

---

## Paso 4 — Registrar el template en la base de datos

```sql
INSERT INTO batch.document_templates (name, domain, gcs_key, version, status, description)
VALUES (
    'seguros-contrato-v1',
    'SEGUROS',
    'templates/seguros/seguros-contrato-v1-1.0.0.jasper',
    '1.0.0',
    'ACTIVE',
    'Contrato de seguro de vida - versión inicial'
);
```

> El campo `name` debe coincidir exactamente con el `templateName` que envía el productor Kafka.
> Solo puede haber un template `ACTIVE` por `name`.

**Para desactivar una versión anterior antes de activar la nueva:**
```sql
UPDATE batch.document_templates
SET status = 'DEPRECATED', updated_at = now()
WHERE name = 'seguros-contrato-v1' AND version = '0.9.0';
```

---

## Paso 5 — Verificar el producer

El servicio productor debe enviar eventos con el `templateName` y `domain` correctos:

```json
{
  "idempotencyId": "uuid-unico",
  "domain":        "seguros",
  "templateName":  "seguros-contrato-v1",
  "fields": {
    "nombreAsegurado": "Juan Pérez",
    "montoCobertura":  "50000000",
    "vigencia":        "2026-05-01"
  }
}
```

> `domain` debe coincidir (case-insensitive) con el valor del enum `DocumentDomain`.
> `templateName` debe coincidir exactamente con la columna `name` en `batch.document_templates`.

---

## Paso 6 — Test de humo

Producir un evento de prueba manualmente:

```bash
docker-compose exec kafka kafka-console-producer \
  --broker-list localhost:9092 \
  --topic document.generation.requested \
  --property "parse.key=true" \
  --property "key.separator=:"
```

Payload:
```
test-uuid-001:{"idempotencyId":"test-uuid-001","domain":"seguros","templateName":"seguros-contrato-v1","fields":{"nombreAsegurado":"Juan Pérez","montoCobertura":"50000000","vigencia":"2026-05-01"}}
```

Verificar inserción:
```sql
SELECT process_id, template_name, status, retry_count
FROM batch.raw_data
WHERE idempotency_id = 'test-uuid-001';
```

Forzar ejecución del Job:
```bash
curl -X POST http://localhost:8080/batch-document-generator/v1/jobs/document-generation/run
```

Verificar resultado:
```sql
SELECT process_id, template_name, status, document_url, error_message
FROM batch.raw_data
WHERE idempotency_id = 'test-uuid-001';
```

El `document_url` debe apuntar a `gs://batch-document-generator/documents/seguros/test-uuid-001.pdf`.

---

## Errores comunes

| Error | Causa | Solución |
|-------|-------|----------|
| `TemplateNotFoundException: seguros-contrato-v1` | No existe registro `ACTIVE` en `document_templates` | Ejecutar el INSERT del paso 4 |
| `TemplateDownloadException` | El archivo `.jasper` no existe en GCS en el path `gcs_key` | Verificar que el upload del paso 3 fue exitoso |
| `IllegalArgumentException: No enum constant ... SEGUROS` | El valor de `domain` no existe en `DocumentDomain` | Agregar el valor al enum (paso 1) y redeployar |
| PDF generado vacío o con campos nulos | Los campos en `fields` no coinciden con las variables del template | Revisar el `.jrxml` y asegurarse que los nombres de campo coincidan |

---

## Versionar un template existente

Cuando se actualiza un template (cambio de diseño, nuevos campos):

1. Compilar el nuevo `.jrxml` → `.jasper` con versión incrementada.
2. Subir el nuevo `.jasper` a GCS con el nuevo path (ej. `templates/seguros/seguros-contrato-v1-2.0.0.jasper`).
3. En una transacción:
   ```sql
   UPDATE batch.document_templates
   SET status = 'DEPRECATED', updated_at = now()
   WHERE name = 'seguros-contrato-v1' AND status = 'ACTIVE';

   INSERT INTO batch.document_templates (name, domain, gcs_key, version, status, description)
   VALUES ('seguros-contrato-v1', 'SEGUROS',
           'templates/seguros/seguros-contrato-v1-2.0.0.jasper',
           '2.0.0', 'ACTIVE', 'Contrato de seguro - v2 con campo de beneficiario');
   ```
4. El `TemplateCache` se vacía automáticamente al iniciar el siguiente Step (scope `@StepScope`). No requiere reinicio de la aplicación.

# Agregar un nuevo dominio

Esta guía cubre los pasos necesarios para incorporar un nuevo dominio funcional al servicio (ej. `SEGUROS`, `CREDITOS`). Un dominio implica un nuevo tipo de documento con su propio template HTML Mustache.

---

## Checklist completo

- [ ] 1. Agregar el valor al enum `DocumentDomain`
- [ ] 2. Crear el template HTML Mustache (`.mustache`)
- [ ] 3. Registrar el template via API (`POST /v1/templates`)
- [ ] 4. Verificar que el productor envía `templateName` y `domain` correctos
- [ ] 5. Ejecutar un test de humo end-to-end

---

## Paso 1 — Agregar el valor al enum `DocumentDomain`

**Archivo:** `src/main/java/ar/com/laboratory/model/enums/DocumentDomain.java`

```java
public enum DocumentDomain {
    MISIONES,
    LOANS,
    SEGUROS   // <- agregar aquí
}
```

El valor del enum determina la subcarpeta de GCS donde se almacenan los documentos generados. Por ejemplo, `SEGUROS` → `gs://<bucket>/seguros/`.

---

## Paso 2 — Crear el template HTML Mustache

El template es un archivo HTML XHTML-válido con variables Mustache. Flying Saucer requiere HTML estrictamente válido y CSS2.

**Convención de nombre:** `{templateName}-{version}.mustache`  
Ejemplo: `seguros-contrato-v1-1.0.0.mustache`

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8"/>
  <style>
    body { font-family: Arial, sans-serif; font-size: 12pt; }
    h1 { color: #333; }
  </style>
</head>
<body>
  <h1>Contrato de Seguro</h1>
  <p>Asegurado: {{nombreAsegurado}}</p>
  <p>Monto de cobertura: {{montoCobertura}}</p>
  <p>Vigencia: {{vigencia}}</p>

  {{#beneficiarios}}
  <p>Beneficiario: {{nombre}} — {{porcentaje}}%</p>
  {{/beneficiarios}}
</body>
</html>
```

**Variables soportadas:**
- `{{variable}}` — valor simple (String, número)
- `{{#lista}}...{{/lista}}` — iteración sobre lista de objetos
- `{{^variable}}...{{/variable}}` — bloque condicional (si variable es falsy)

> El template debe ser XHTML-válido. Etiquetas sin cerrar (como `<br>` o `<img>`) deben escribirse como `<br/>` e `<img ... />`.

---

## Paso 3 — Registrar el template via API

Los templates se almacenan en la columna `content TEXT` de `data.document_templates`. No se usa GCS para templates.

```bash
curl -X POST "http://localhost:8080/document-generator-api/v1/templates" \
  -H "Content-Type: application/json" \
  -d '{
    "name":        "seguros-contrato-v1",
    "domain":      "SEGUROS",
    "version":     "1.0.0",
    "description": "Contrato de seguro de vida - versión inicial",
    "content":     "<html>...</html>"
  }'
```

O via Swagger UI en `http://localhost:8080/document-generator-api/swagger-ui.html`.

> El campo `name` debe coincidir exactamente con el `templateName` que envía el productor Kafka.  
> Solo puede haber un template `ACTIVE` por `name`.

**Para desactivar una versión anterior antes de activar la nueva:**
```sql
UPDATE data.document_templates
SET status = 'DEPRECATED', updated_at = now()
WHERE name = 'seguros-contrato-v1' AND version = '0.9.0';
```

---

## Paso 4 — Verificar el productor

El servicio productor debe enviar eventos con el `templateName` y `domain` correctos:

```json
{
  "idempotencyId": "uuid-unico",
  "domain":        "seguros",
  "templateName":  "seguros-contrato-v1",
  "fields": {
    "nombreAsegurado": "Juan Pérez",
    "montoCobertura":  "50000000",
    "vigencia":        "2026-05-01",
    "beneficiarios": [
      { "nombre": "María Pérez", "porcentaje": "100" }
    ]
  }
}
```

> `domain` debe coincidir (case-insensitive) con el valor del enum `DocumentDomain`.  
> `templateName` debe coincidir exactamente con la columna `name` en `data.document_templates`.

---

## Paso 5 — Test de humo

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
FROM data.raw_data
WHERE idempotency_id = 'test-uuid-001';
```

El scheduler corre automáticamente según el `fixed-delay` configurado (por defecto cada 30 segundos en local). Después de una ejecución, el registro debería pasar a `PUBLISHED`.

Verificar resultado:
```sql
SELECT process_id, template_name, status, document_url, error_message
FROM data.raw_data
WHERE idempotency_id = 'test-uuid-001';
```

El `document_url` debe apuntar a `gs://document-generator-bucket/seguros/test-uuid-001.pdf`.

---

## Errores comunes

| Error | Causa | Solución |
|-------|-------|----------|
| `TemplateNotFoundException: seguros-contrato-v1` | No existe registro `ACTIVE` en `document_templates` | Ejecutar el POST del paso 3 |
| `DocumentGenerationException: ...` | El HTML del template no es XHTML-válido | Revisar el template y asegurarse de que esté bien formado |
| `IllegalArgumentException: No enum constant ... SEGUROS` | El valor de `domain` no existe en `DocumentDomain` | Agregar el valor al enum (paso 1) y redeployar |
| PDF generado vacío o con campos nulos | Los campos en `fields` no coinciden con las variables Mustache del template | Verificar que los nombres de variable coincidan exactamente (case-sensitive) |

---

## Versionar un template existente

Cuando se actualiza un template (cambio de diseño, nuevos campos):

1. Registrar la nueva versión via API con `version` incrementada y el nuevo contenido HTML.
2. En una transacción, deprecar la versión anterior:
   ```sql
   UPDATE data.document_templates
   SET status = 'DEPRECATED', updated_at = now()
   WHERE name = 'seguros-contrato-v1' AND status = 'ACTIVE';
   ```
3. Activar la nueva versión (o hacerlo directamente via `PUT /v1/templates/{name}/status`).
4. El `TemplateCache` invalida la entrada al guardar la nueva versión via API (`TemplateService` llama a `templateCache.invalidate(name)`). No requiere reinicio de la aplicación.

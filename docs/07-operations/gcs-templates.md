# Gestión de templates

Los templates de documentos son archivos **HTML Mustache** cuyo contenido se almacena directamente en la columna `content TEXT` de `data.document_templates`. No se usa GCS para los templates en runtime — GCS solo almacena los **documentos PDF generados**.

---

## Estructura de almacenamiento

```
PostgreSQL — data.document_templates
├── content TEXT   ← HTML Mustache completo (fuente de verdad)
├── name           ← identificador lógico (ej. compra-y-gana-personas-v1)
├── domain         ← MISIONES | ...
├── version        ← 1.0.0
└── status         ← ACTIVE | INACTIVE | DEPRECATED

GCS — document-generator-bucket
├── misiones/      ← PDFs generados (output)
│   └── <idempotencyId>.pdf
└── otro-dominio/
    └── <idempotencyId>.pdf
```

Los templates **no viven en GCS**. El bucket solo recibe los PDFs que el scheduler genera.

---

## Convención de nombres

```
{nombre-logico}-{variante}-v{N}
```

El campo `name` en la BD es el identificador que llega en el evento Kafka como `templateName`. Ejemplos:
- `compra-y-gana-personas-v1`
- `cashback-mensual-v1`
- `programa-referidos-v2`

---

## Registrar un template nuevo (vía API — recomendado)

```bash
curl -X POST "http://localhost:8080/document-generator-api/v1/templates" \
  -F "file=@mi-template.mustache" \
  -F "name=mi-template-v1" \
  -F "domain=MISIONES" \
  -F "version=1.0.0" \
  -F "description=Template para certificados de la campaña"
```

La respuesta incluye las variables Mustache detectadas:

```json
{
  "id": "3fa85f64-...",
  "name": "mi-template-v1",
  "domain": "MISIONES",
  "version": "1.0.0",
  "status": "ACTIVE",
  "detectedFields": ["nombre", "rut", "monto", "condiciones"]
}
```

La API guarda el HTML en `data.document_templates.content` e invalida el `TemplateCache`. No se requiere acceso directo a GCS ni a la BD.

---

## Actualizar un template existente

```bash
curl -X POST "http://localhost:8080/document-generator-api/v1/templates" \
  -F "file=@mi-template-v2.mustache" \
  -F "name=mi-template-v1" \
  -F "domain=MISIONES" \
  -F "version=2.0.0" \
  -F "description=Template actualizado con nuevo campo de beneficiario"
```

El registro existente se actualiza (no se crea uno nuevo). El scheduler usará el HTML recompilado a partir del siguiente ciclo.

---

## Verificar variables del template

```bash
GET http://localhost:8080/document-generator-api/v1/templates/mi-template-v1/fields
```

Respuesta:
```json
["beneficiario", "condiciones", "fecha", "monto", "nombre", "rut"]
```

Estas variables deben enviarse como claves en el `data` JSON del evento Kafka. Las variables de listas (`{{#lista}}`) aparecen como el nombre de la sección raíz.

---

## Insertar directamente en BD (seed / staging)

```sql
INSERT INTO data.document_templates (name, domain, version, status, description, content)
VALUES (
    'mi-template-v1',
    'MISIONES',
    '1.0.0',
    'ACTIVE',
    'Template de ejemplo',
    $T$<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html ...>
<html>...</html>$T$
);
```

---

## Listar templates registrados

```bash
# Via API
GET http://localhost:8080/document-generator-api/v1/templates

# Via BD
SELECT name, domain, version, status, updated_at,
       length(content) AS content_bytes
FROM data.document_templates
ORDER BY name;
```

---

## Desactivar un template

```sql
UPDATE data.document_templates
SET status = 'DEPRECATED', updated_at = now()
WHERE name = 'mi-template-v1' AND status = 'ACTIVE';
```

Los registros `PENDING` con ese `template_name` fallarán con `TemplateNotFoundException` en el próximo ciclo → `ERROR` → `FAILED`. Manejar el backlog antes de desactivar.

---

## Invalidar la caché manualmente

El `TemplateCache` se invalida automáticamente cuando se actualiza un template vía API. Si se modifica el `content` directamente en BD, el caché no se invalida solo — es necesario reiniciar el servicio o invocar la API de actualización.

```bash
# Re-subir el mismo archivo para forzar invalidación vía API
curl -X POST "http://localhost:8080/document-generator-api/v1/templates" \
  -F "file=@mi-template-v1.mustache" \
  -F "name=mi-template-v1" \
  -F "domain=MISIONES" \
  -F "version=1.0.0"
```

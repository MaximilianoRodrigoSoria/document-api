# Templates de documentos

Un **DocumentTemplate** es el registro en `data.document_templates` que vincula un nombre lógico de template con su contenido HTML Mustache y su dominio de negocio.

## Estructura del catálogo

```sql
SELECT name, domain, version, status, description FROM data.document_templates;
```

| Columna | Descripción | Ejemplo |
|---------|-------------|---------|
| `name` | Nombre lógico único; coincide con `raw_data.template_name` | `compra-y-gana-personas-v1` |
| `domain` | Dominio funcional en mayúsculas | `MISIONES` |
| `content` | HTML Mustache completo del template (fuente de verdad) | `<?xml version...` |
| `gcs_key` | Referencia histórica al objeto en GCS (nullable, no se usa en runtime) | `templates/...` |
| `version` | Versión semántica del template | `1.0.0` |
| `status` | `ACTIVE`, `INACTIVE`, `DEPRECATED` | `ACTIVE` |

Solo los templates con `status = ACTIVE` son usados por el `TemplateCache`.

---

## Tipo de template soportado: HTML Mustache + Flying Saucer

Los templates son archivos **HTML válidos con variables Mustache** (`{{variable}}`, `{{#lista}}...{{/lista}}`). El `HtmlPdfDocumentGenerator` renderiza el HTML con los datos del evento Kafka y lo convierte a PDF en-proceso usando Flying Saucer.

El contenido del template se almacena directamente en la columna `content TEXT` de la tabla. No se requiere descarga de GCS en runtime — el `TemplateCache` lee desde la entidad BD y compila el template una sola vez.

### Variables simples

```html
<p>Cliente: {{nombre}}</p>
<p>RUT: {{rut}}</p>
```

### Secciones dinámicas (listas)

```html
{{#categorias}}
<tr>
  <td>{{categoria}}</td>
  <td>{{etapa1Premio}}</td>
  <td>{{etapa2Premio}}</td>
</tr>
{{/categorias}}
```

**Regla clave:** los nombres de las variables Mustache deben coincidir exactamente con las claves del JSON que llega en `raw_data.data`. Para consultar las variables de un template registrado:

```bash
GET /v1/templates/{name}/fields
```

---

## Templates disponibles (seed inicial)

| Name | Dominio | Variables clave | Estructura |
|------|---------|-----------------|------------|
| `compra-y-gana-personas-v1` | MISIONES | `{{#categorias}}`, `{{etapa1Premio}}`, `{{etapa2Premio}}` | Tabla de dos etapas por categoría |
| `compra-y-gana-empresas-v1` | MISIONES | `{{razonSocial}}`, `{{#tramosFacturacion}}`, `{{cashback}}` | Escala de facturación |
| `cashback-mensual-v1` | MISIONES | `{{montoCashback}}`, `{{porcentajeCashback}}`, `{{fechaAcreditacion}}` | Liquidación flat |
| `programa-referidos-v1` | MISIONES | `{{#referidos}}`, `{{nombreReferido}}`, `{{totalPremio}}` | Lista dinámica de referidos |

---

## Registrar un template nuevo

### Via API (recomendado)

```bash
curl -X POST "http://localhost:8080/document-generator-api/v1/templates" \
  -F "file=@mi-template.mustache" \
  -F "name=mi-template-v1" \
  -F "domain=MISIONES" \
  -F "version=1.0.0" \
  -F "description=Template para certificados de misión"
```

La respuesta incluye las variables Mustache detectadas en el HTML:

```json
{
  "id": "...",
  "name": "mi-template-v1",
  "domain": "MISIONES",
  "version": "1.0.0",
  "status": "ACTIVE",
  "detectedFields": ["nombre", "rut", "monto", "fecha"]
}
```

El endpoint guarda el contenido del template en la columna `content` de `data.document_templates` e invalida el `TemplateCache`. No se requiere acceso directo a GCS ni a la BD.

---

## Ciclo de vida de un template

```
POST /v1/templates  →  INSERT data.document_templates (content=HTML, status=ACTIVE)
                              |
                   [TemplateCache compila el Mustache en primer uso]
                              |
                 [Nueva versión disponible]
                              |
         POST /v1/templates (mismo name)  →  UPDATE content + invalida caché
```

Al subir una nueva versión con el mismo `name`, el registro existente se actualiza directamente. El scheduler toma la versión recompilada en el siguiente ciclo.

---

## Desactivar un template

```sql
UPDATE data.document_templates
SET status = 'DEPRECATED', updated_at = now()
WHERE name = 'mi-template-v1' AND status = 'ACTIVE';
```

Los registros `PENDING` con ese `template_name` fallarán con `TemplateNotFoundException` en el próximo ciclo y pasarán a `ERROR` (y eventualmente `FAILED`). Manejar el backlog antes de desactivar.

---

## Restricciones del HTML para Flying Saucer

Flying Saucer convierte HTML a PDF usando CSS2. El template debe cumplir:

| Restricción | Detalle |
|-------------|---------|
| XHTML válido | Declaración `<?xml...?>`, DOCTYPE XHTML 1.0, tags cerrados |
| Solo CSS2 | Sin `flexbox`, `grid` ni propiedades CSS3 |
| Layout tabular | Usar `<table>` para columnas y alineaciones |
| Entidades HTML | Usar `&#8212;` en lugar de `—`, `&#225;` en lugar de `á`, etc. |

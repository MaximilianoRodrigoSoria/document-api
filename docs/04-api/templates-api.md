# Templates API — /v1/templates

API REST para gestión del catálogo de templates HTML Mustache. Permite crear, editar, previsualizar y administrar el ciclo de vida de cada template sin acceso directo a la base de datos.

## Base URL

```
http://localhost:8080/document-generator-api/v1/templates
```

---

## Flujo de uso esperado (backoffice)

1. El operador sube el archivo `.mustache` via `POST /v1/templates` — la respuesta incluye las variables detectadas.
2. El equipo de backend configura el evento Kafka con esas variables como claves en el campo `data`.
3. Para editar: `GET /v1/templates/{name}` carga el HTML en el editor Monaco del backoffice.
4. El operador modifica el HTML y guarda con `PUT /v1/templates/{name}`.
5. El scheduler usa el template recompilado en el próximo ciclo.

---

## Endpoints

### POST / — Registrar o reemplazar un template

Sube un archivo `.mustache` (HTML con variables `{{campo}}`), guarda su contenido en la columna `content TEXT` de `data.document_templates` e invalida el `TemplateCache`. Si el `name` ya existe, actualiza el registro existente.

**Request:**

```
POST /v1/templates
Content-Type: multipart/form-data
```

| Parametro | Tipo | Requerido | Descripcion |
|-----------|------|-----------|-------------|
| `file` | multipart | Si | Archivo `.mustache` con HTML Mustache |
| `name` | query string | Si | Nombre logico unico del template (ej. `compra-y-gana-v2`) |
| `domain` | query string | Si | Dominio funcional. Valor del enum `DocumentDomain` (ej. `MISIONES`) |
| `version` | query string | Si | Version semantica (ej. `1.0.0`) |
| `description` | query string | No | Descripcion del template |

**Response 201 Created:**

```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "name": "compra-y-gana-v2",
  "domain": "MISIONES",
  "version": "1.0.0",
  "status": "ACTIVE",
  "description": "Template actualizado para compra y gana",
  "detectedFields": ["cashback", "fecha", "nombre", "rut"]
}
```

`detectedFields` lista las variables Mustache `{{campo}}` detectadas en el HTML, ordenadas alfabeticamente. Estas deben enviarse como claves en el `data` del evento Kafka.

---

### GET / — Listar templates

Retorna todos los templates del catalogo, ordenados por nombre. No incluye el contenido HTML.

**Response 200 OK:**

```json
[
  {
    "id": "a1b2c3d4-...",
    "name": "compra-y-gana-personas-v1",
    "domain": "MISIONES",
    "version": "1.0.0",
    "status": "ACTIVE",
    "description": "Template para mision compra y gana",
    "createdAt": "2026-05-09T00:00:00",
    "updatedAt": "2026-05-09T00:00:00"
  }
]
```

---

### GET /{name} — Detalle completo con content y variables

Retorna metadatos, el contenido HTML completo y las variables Mustache detectadas. Es el endpoint principal del backoffice: carga el HTML en el editor Monaco y muestra las variables esperadas en el payload Kafka.

**Response 200 OK:**

```json
{
  "id": "a1b2c3d4-...",
  "name": "compra-y-gana-personas-v1",
  "domain": "MISIONES",
  "version": "1.0.0",
  "status": "ACTIVE",
  "description": "Template para mision compra y gana",
  "content": "<?xml version=\"1.0\"?>...<body>{{nombre}}</body>...</html>",
  "detectedFields": ["cashback", "categorias", "nombre", "rut"],
  "createdAt": "2026-05-09T00:00:00",
  "updatedAt": "2026-05-09T00:00:00"
}
```

**Errores:**

| HTTP | Motivo |
|------|--------|
| 404 | Template no encontrado |

---

### PUT /{name} — Actualizar contenido HTML

Persiste el HTML editado desde el backoffice, invalida el cache del scheduler y retorna el template actualizado con las variables detectadas. El template queda en estado `ACTIVE` tras la actualizacion.

**Request:**

```
PUT /v1/templates/{name}
Content-Type: application/json
```

```json
{
  "domain": "MISIONES",
  "version": "1.1.0",
  "description": "Version con nuevo campo de beneficiario",
  "content": "<?xml version=\"1.0\"?>...HTML MUSTACHE..."
}
```

| Campo | Validacion |
|-------|------------|
| `domain` | Requerido. Valor del enum `DocumentDomain` |
| `version` | Requerido. Formato semver `\d+\.\d+\.\d+` |
| `description` | Opcional. Maximo 1000 caracteres |
| `content` | Requerido. No puede estar en blanco |

**Response 200 OK:** misma estructura que `GET /{name}` con el contenido y variables actualizados.

**Errores:**

| HTTP | Motivo |
|------|--------|
| 400 | `content` vacio, `version` con formato invalido |
| 404 | Template no encontrado |

---

### PATCH /{name}/status — Cambiar estado

Transiciona el template a `ACTIVE`, `INACTIVE` o `DEPRECATED`. Al desactivar un template activo, el cache se invalida automaticamente.

**Request:**

```json
{ "status": "INACTIVE" }
```

**Response 200 OK:**

```json
{
  "id": "...",
  "name": "compra-y-gana-personas-v1",
  "status": "INACTIVE"
}
```

**Errores:**

| HTTP | Motivo |
|------|--------|
| 400 | `status` nulo o valor invalido |
| 404 | Template no encontrado |

---

### GET /{name}/preview — Visualizar HTML en el browser

Retorna el HTML crudo con `Content-Type: text/html`. Las variables `{{campo}}` se muestran sin sustituir.

**Response 200 OK:** `Content-Type: text/html`

**Errores:** 404 si no existe.

---

### GET /{name}/download — Descargar archivo HTML

Retorna el HTML como descarga. El archivo se nombra `{name}.html`.

**Response 200 OK:**

```
Content-Disposition: attachment; filename="compra-y-gana-personas-v1.html"
Content-Type: text/html
```

**Errores:** 404 si no existe.

---

### GET /{name}/fields — Variables Mustache

Retorna la lista de variables `{{campo}}` detectadas, ordenadas alfabeticamente. Estas deben enviarse como claves en el `data` del evento Kafka.

**Response 200 OK:**

```json
["cashback", "categorias", "etapa1Premio", "etapa2Premio", "nombre", "rut"]
```

**Errores:** 404 si no existe.

---

## Resumen de endpoints

| Metodo | Endpoint | Descripcion | HTTP OK |
|--------|----------|-------------|---------|
| `POST` | `/v1/templates` | Sube o reemplaza template `.mustache` | 201 |
| `GET` | `/v1/templates` | Lista todos los templates (sin content) | 200 |
| `GET` | `/v1/templates/{name}` | Detalle completo (content + variables) | 200 |
| `PUT` | `/v1/templates/{name}` | Edita HTML desde el backoffice | 200 |
| `PATCH` | `/v1/templates/{name}/status` | Activa / Pausa / Depreca | 200 |
| `GET` | `/v1/templates/{name}/preview` | HTML en browser (`text/html`) | 200 |
| `GET` | `/v1/templates/{name}/download` | Descarga archivo `.html` | 200 |
| `GET` | `/v1/templates/{name}/fields` | Lista variables `{{campo}}` | 200 |

---

## Relacion con el evento Kafka

El campo `templateName` del evento `document.generation.requested` debe coincidir exactamente con el `name` del template. Las claves del objeto `data` deben coincidir con las variables Mustache del HTML.

```json
{
  "idempotencyId": "550e8400-e29b-41d4-a716-446655440000",
  "domain": "MISIONES",
  "templateName": "compra-y-gana-personas-v1",
  "data": {
    "nombre": "Juan Perez",
    "rut": "12.345.678-9",
    "cashback": "$50.000",
    "categorias": [
      { "categoria": "Supermercados", "etapa1Premio": "$5.000", "etapa2Premio": "$10.000" }
    ]
  }
}
```

Consultar las variables sin abrir el HTML:

```bash
GET /v1/templates/compra-y-gana-personas-v1/fields
```

---

## Backoffice Monaco Editor

El backoffice standalone (`docs/backoffice/template-editor.html`) expone una interfaz de edicion sin servidor de build ni dependencias.

- Sidebar con lista de templates, estado y dominio
- Editor Monaco (VS Code) con syntax highlighting HTML
- Deteccion de variables `{{campo}}` en tiempo real
- Botones de estado: Activar / Pausar / Deprecar
- Preview del HTML en iframe embebido
- Guardado via `PUT /{name}`

---

## Swagger UI

```
http://localhost:8080/document-generator-api/swagger-ui.html
```

Tag: `Templates`

# Storage API — /v1/storage

API REST para gestión genérica de archivos en Google Cloud Storage. Permite subir, listar, descargar y eliminar archivos del bucket sin acceso directo a GCS.

> Para gestionar **templates PDF** (subir, versionar, parsear campos AcroForm), usar [Templates API](templates-api.md) en lugar de esta API.

## Base URL

```
http://localhost:8080/document-generator-api/v1/storage
```

---

## Endpoints

### POST /files — Subir un archivo

Sube un archivo al bucket bajo la clave `{folder}{filename}`.

**Request:**

```
POST /v1/storage/files?folder=templates/&overwrite=false
Content-Type: multipart/form-data

file: <archivo binario>
```

| Parámetro | Tipo | Requerido | Descripción |
|-----------|------|-----------|-------------|
| `folder` | query string | No | Prefijo de carpeta. Debe terminar con `/`. Vacío = raíz del bucket. |
| `overwrite` | query string | No (default: `false`) | Si `true`, reemplaza el archivo si ya existe. |
| `file` | multipart | Sí | Archivo a subir. |

**Response 201 Created:**

```json
{
  "gcsKey": "templates/mi-template.pdf",
  "bucket": "document-generator-bucket",
  "size": 204800,
  "contentType": "application/pdf",
  "uploadedAt": "2026-05-09T15:30:00Z",
  "gcsUri": "gs://document-generator-bucket/templates/mi-template.pdf"
}
```

**Errores:**

| HTTP | Motivo |
|------|--------|
| 400 | Folder inválido (path traversal, no termina con `/`) |
| 409 | El archivo ya existe y `overwrite=false` |

---

### GET /files — Listar archivos

Lista archivos en el bucket con paginación por cursor.

**Request:**

```
GET /v1/storage/files?folder=templates/&pageSize=20&pageToken=<token>
```

| Parámetro | Tipo | Descripción |
|-----------|------|-------------|
| `folder` | query string | Prefijo a filtrar. Vacío = todo el bucket. |
| `pageSize` | query string | Resultados por página (1-100, default: 20) |
| `pageToken` | query string | Token de la página siguiente (obtenido de la respuesta anterior) |

**Response 200 OK:**

```json
{
  "folder": "templates/",
  "pageSize": 20,
  "nextPageToken": null,
  "files": [
    {
      "gcsKey": "templates/compra-y-gana-empresas-prueba-002.pdf",
      "bucket": "document-generator-bucket",
      "name": "compra-y-gana-empresas-prueba-002.pdf",
      "size": 204800,
      "contentType": "application/pdf",
      "createdAt": "2026-05-09T00:00:00Z",
      "updatedAt": "2026-05-09T00:00:00Z",
      "gcsUri": "gs://document-generator-bucket/templates/compra-y-gana-empresas-prueba-002.pdf"
    }
  ]
}
```

Cuando no hay más páginas, `nextPageToken` es `null`.

---

### GET /files/content — Descargar contenido binario

Descarga el contenido de un archivo. La clave GCS se pasa como query param `gcsKey`. Retorna los bytes con `Content-Disposition: attachment`.

> **Nota de implementación:** Spring Boot 3.x (`PathPatternParser`) no admite `**` en posición media de un patrón (p. ej., `/files/**/content` falla al arrancar). Por eso la clave va en el query param.

**Request:**

```
GET /v1/storage/files/content?gcsKey=templates/compra-y-gana-empresas-prueba-002.pdf
```

**Response 200 OK:**

```
Content-Type: application/pdf
Content-Disposition: attachment; filename="compra-y-gana-empresas-prueba-002.pdf"
Content-Length: 204800

<bytes del archivo>
```

**Errores:**

| HTTP | Motivo |
|------|--------|
| 400 | `gcsKey` ausente o vacío |
| 404 | El archivo no existe en GCS |

---

### GET /files/** — Metadatos de un archivo

Retorna los metadatos de un archivo sin descargarlo.

**Request:**

```
GET /v1/storage/files/templates/compra-y-gana-empresas-prueba-002.pdf
```

**Response 200 OK:**

```json
{
  "gcsKey": "templates/compra-y-gana-empresas-prueba-002.pdf",
  "bucket": "document-generator-bucket",
  "name": "compra-y-gana-empresas-prueba-002.pdf",
  "size": 204800,
  "contentType": "application/pdf",
  "createdAt": "2026-05-09T00:00:00Z",
  "updatedAt": "2026-05-09T00:00:00Z",
  "gcsUri": "gs://document-generator-bucket/templates/compra-y-gana-empresas-prueba-002.pdf"
}
```

**Errores:**

| HTTP | Motivo |
|------|--------|
| 404 | El archivo no existe en GCS |

---

### DELETE /files/** — Eliminar un archivo

**Request:**

```
DELETE /v1/storage/files/templates/compra-y-gana-empresas-prueba-002.pdf
```

**Response:** `204 No Content`

**Errores:**

| HTTP | Motivo |
|------|--------|
| 404 | El archivo no existe en GCS |

---

## Notas de implementación

- `GET /files/content` (literal) tiene precedencia sobre `GET /files/**` (wildcard) en `PathPatternParser`.
- La `gcsKey` en metadata y delete se extrae del atributo `PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE`, soportando paths con múltiples segmentos.
- Los "folder markers" (objetos de tamaño 0 que terminan en `/`) se excluyen del listado.

## Swagger UI

```
http://localhost:8080/document-generator-api/swagger-ui.html
```

Tag: `Storage`

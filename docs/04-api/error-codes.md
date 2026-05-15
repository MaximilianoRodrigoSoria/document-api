# Catalogo de errores HTTP

Todas las excepciones son mapeadas por `GlobalControllerAdvice` a respuestas HTTP estructuradas.

## Formato de error

```json
{
  "status":    404,
  "error":     "Not Found",
  "message":   "File not found in GCS: 'templates/missing.pdf'",
  "timestamp": "2026-05-08T15:30:00Z",
  "path":      "/v1/storage/files/templates/missing.pdf"
}
```

---

## Errores de Storage API

| HTTP | Excepcion | Causa | Solucion |
|------|-----------|-------|----------|
| 400 | `StorageInvalidFolderException` | El parametro `folder` contiene `..` (path traversal), empieza con `/`, o no termina con `/` | Usar formato `templates/` — relativo y con trailing slash |
| 404 | `StorageFileNotFoundException` | El objeto no existe en GCS | Verificar la `gcsKey` con `GET /files/` (listado) |
| 409 | `StorageFileAlreadyExistsException` | El archivo ya existe en GCS y `overwrite=false` | Pasar `?overwrite=true` o usar una `gcsKey` distinta |

---

## Errores de generacion de documentos

| HTTP | Excepcion | Causa |
|------|-----------|-------|
| 500 | `DocumentGenerationException` | Fallo al generar el PDF o al subirlo a GCS. Visible en logs con contexto MDC. |
| 404 | `TemplateNotFoundException` | El `templateName` del evento no existe en `batch.document_templates` con `status = ACTIVE` |
| 409 | `IdempotencyConflictException` | Intento de insertar un `idempotency_id` que ya existe y no es `FAILED` |

---

## Errores de descarga de templates

| HTTP | Excepcion | Causa |
|------|-----------|-------|
| 500 | `TemplateDownloadException` | Fallo al descargar el template desde GCS durante el procesamiento batch |

---

## Errores de Jobs API

| HTTP | Causa |
|------|-------|
| 500 | El `JobLauncher` fallo al lanzar el Job (ej. ya hay una ejecucion activa, error de configuracion) |

---

## Errores genericos

| HTTP | Causa |
|------|-------|
| 400 | Validacion de campos requeridos (`@NotNull`, `@NotBlank`) |
| 400 | Argumento ilegal (`IllegalArgumentException`) — ej. archivo vacio, nombre de archivo en blanco |
| 500 | Error inesperado no mapeado |

---

## Notas para consumidores

- Un `404` en `GET /files/{gcsKey}` NO indica que el servicio este caido; solo que el objeto no existe en GCS.
- Un `409` en upload es esperado si se intenta subir el mismo archivo dos veces sin `overwrite=true`.
- Los errores `5xx` deben loguearse y alertarse. Incluyen un `message` con contexto suficiente para debugging.

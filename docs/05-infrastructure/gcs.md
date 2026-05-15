# Google Cloud Storage — Configuración y estructura

## Bucket

| Propiedad | Valor |
|-----------|-------|
| Nombre | `document-generator-bucket` (configurable via `gcs.bucket-name`) |
| Acceso | Privado. Sin acceso público. |
| Región | Según el proyecto GCP del ambiente |
| Emulador local | `fake-gcs-server` en `http://localhost:4443` |

---

## Estructura de carpetas

```
gs://<bucket>/
    misiones/              ← Documentos PDF generados del dominio MISIONES
    loans/                 ← (futuro) Documentos del dominio LOANS
    documents/             ← Fallback si el campo `type` del registro es nulo
    <domain>/              ← Patrón general: domain en minúsculas
```

> **Nota:** Los templates HTML Mustache **no se almacenan en GCS**. Viven en la columna `content TEXT` de `data.document_templates`. GCS solo recibe los PDFs que el scheduler genera como output.

### Regla de derivación de carpeta destino

La carpeta del documento generado se deriva del campo `type` del registro `raw_data`:

```java
String folder = (domain != null && !domain.isBlank())
        ? domain.toLowerCase().trim()
        : "documents";

String gcsKey = folder + "/" + idempotencyId + ".pdf";
```

**Ejemplo:** si `raw_data.type = "MISIONES"`, el PDF se sube a `misiones/<idempotencyId>.pdf`.

---

## Autenticación

### Producción (Service Account / Workload Identity)

El pod tiene un Google Service Account (GSA) con los permisos necesarios, configurado via Workload Identity (GKE) o credenciales explícitas. No se requieren credenciales hardcodeadas en el código.

Permisos mínimos del GSA:

| Permiso | Para qué |
|---------|----------|
| `storage.objects.create` | Upload de documentos generados |
| `storage.objects.get` | Download de documentos via Storage API |
| `storage.objects.delete` | Eliminación de archivos via Storage API |
| `storage.objects.list` | Listado de archivos via Storage API |

### Local (fake-gcs-server)

El `GcsConfig` detecta la presencia de `gcs.emulator-host` y configura el cliente para apuntar al emulador:

```yaml
# application-local.yml
gcs:
  emulator-host: http://localhost:4443
  project-id:    local-project
  bucket-name:   document-generator-bucket
```

No se requieren credenciales reales. El emulador acepta cualquier token.

### Service Account explícito (opcional)

Si se prefiere autenticación explícita (no Workload Identity), setear `gcs.credentials-json` con el JSON del service account:

```yaml
gcs:
  credentials-json: |
    { "type": "service_account", ... }
```

---

## GcsStorageClient — Operaciones disponibles

| Método | Descripción |
|--------|-------------|
| `uploadDocument(idempotencyId, domain, pdfBytes)` | Sube el PDF generado bajo `{domain}/{idempotencyId}.pdf` |
| `uploadFile(gcsKey, content, contentType)` | Sube un archivo arbitrario |
| `downloadFile(gcsKey)` | Descarga un archivo arbitrario |
| `getBlob(gcsKey)` | Retorna metadatos del objeto (sin descarga) |
| `fileExists(gcsKey)` | Verifica existencia sin descargar |
| `listFiles(prefix, pageToken, pageSize)` | Lista objetos con paginación por cursor |
| `deleteFile(gcsKey)` | Elimina un objeto |
| `buildGcsUri(gcsKey)` | Construye la URI `gs://bucket/key` |
| `getBucket()` | Retorna el nombre del bucket configurado |

> `downloadTemplate(gcsKey)` fue removido — los templates ya no se descargan de GCS en runtime.

---

## Inicialización del bucket local

El `docker-compose.yml` incluye un servicio `fake-gcs-server`. El bucket se crea automáticamente al arrancar el servidor. Verificar con:

```bash
curl http://localhost:4443/storage/v1/b
```

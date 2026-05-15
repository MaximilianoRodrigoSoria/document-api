# Arquitectura — document-generator-api

## Visión general

`document-generator-api` es un microservicio de generación asíncrona de documentos PDF. Recibe solicitudes via Kafka, las persiste en PostgreSQL con estado `PENDING`, las procesa con un scheduler (generación PDF + upload a GCS) y publica los resultados de vuelta a Kafka.

**Stack:** Spring Boot 3.3.3 · Java 21 · PostgreSQL 15.2 · Kafka (KRaft) · Google Cloud Storage · Mustache.java · Flying Saucer

---

## Flujo completo

```
Productor externo
    |
    v Kafka [document.generation.requested]
    |
    v DocumentGenerationRequestedConsumer
    |   - Verifica idempotency_id (UNIQUE)
    |   - Si ya existe y no es FAILED → ACK sin persistir
    |   - Si es FAILED → re-insertar como PENDING
    |   - INSERT data.raw_data (status = PENDING, retry_count = 0)
    |
    v @Scheduled(fixedDelay) → DocumentGenerationScheduler
    |
    +-- TX1: recoverStuckProcessing()
    |   - UPDATE PROCESSING → PENDING (si updated_at > timeout configurado)
    |
    +-- TX2: claimPendingRecords()
    |   - SELECT PENDING/ERROR FOR UPDATE SKIP LOCKED → UPDATE PROCESSING
    |
    +-- Por cada registro (secuencial dentro del ciclo):
    |   ├── templateCache.getCompiledTemplate(templateName)
    |   │     ├── CACHE HIT  → Mustache compilado desde memoria (O(1))
    |   │     └── CACHE MISS → query BD (lee content TEXT) + compila Mustache
    |   │
    |   ├── deserializar data (JSONB) → Map<String, Object>
    |   │
    |   ├── htmlPdfDocumentGenerator.generate(compiledTemplate, fields)
    |   │     ├── Mustache.execute(fields) → HTML string
    |   │     └── Flying Saucer ITextRenderer → PDF bytes (~20 ms)
    |   │
    |   ├── gcsStorageClient.uploadDocument(idempotencyId, domain, pdfBytes)
    |   │     └── destino: {domain}/{idempotencyId}.pdf
    |   │
    |   ├── TX3: markGenerated(record, documentUrl)  → status = GENERATED
    |   │
    |   ├── publisher.publish(topic, event)           → Kafka
    |   │
    |   └── TX4: markPublished(record)                → status = PUBLISHED
    |
    v Kafka [document.generation.completed]
    |
    v Servicio productor externo
        - Recibe idempotency_id + document_url
        - Actualiza su dominio con el link del documento
```

---

## Máquina de estados — `raw_data.status`

```
PENDING
   |
   v  Scheduler (claimPendingRecords)
PROCESSING ── error ──> ERROR ── retry_count >= 3 ──> FAILED
   |
   v  OK
GENERATED
   |
   v  Scheduler (publicación Kafka)
PUBLISHED
```

| Estado | Descripción |
|--------|-------------|
| `PENDING` | Solicitud recibida y persistida por el consumer de Kafka |
| `PROCESSING` | Registro tomado por el scheduler (transitorio) |
| `GENERATED` | PDF generado y URL en GCS disponible |
| `PUBLISHED` | Evento publicado a Kafka — ciclo completo |
| `ERROR` | Falló el procesamiento; se reintentará si `retry_count < MAX_RETRY` |
| `FAILED` | Superó el límite de reintentos; requiere intervención manual |

---

## Fronteras transaccionales

El scheduler separa las operaciones de I/O externo (GCS, Kafka) de las transacciones de BD para no mantener conexiones abiertas durante llamadas de red.

| TX | Método en `DocumentGenerationService` | Operación |
|----|---------------------------------------|-----------|
| TX1 | `recoverStuckProcessing()` | bulk UPDATE PROCESSING → PENDING |
| TX2 | `claimPendingRecords()` | SELECT FOR UPDATE SKIP LOCKED → UPDATE PROCESSING |
| TX3 | `markGenerated()` | UPDATE GENERATED + document_url + processed_at |
| TX4 | `markPublished()` | UPDATE PUBLISHED + published_at |
| TX5 | `markError()` | UPDATE ERROR/FAILED + retry_count + error_message |

---

## Decisiones técnicas clave

### Idempotencia
`idempotency_id` tiene constraint `UNIQUE` en `data.raw_data`. El consumer verifica si el ID ya existe antes de insertar. Si existe y no es `FAILED` → ACK sin persistir. Si es `FAILED` → reinsertar como `PENDING`.

### Concurrencia — FOR UPDATE SKIP LOCKED
`claimPendingRecords()` usa `FOR UPDATE SKIP LOCKED`, lo que permite ejecutar múltiples réplicas del servicio sin colisiones: dos pods nunca toman el mismo registro.

### TemplateCache (singleton)
`TemplateCache` es un `@Component` singleton con dos `ConcurrentHashMap`: `entityCache` (metadata BD) y `compiledCache` (template Mustache compilado). El primer acceso por template lee el `content` de la BD y compila el Mustache; los siguientes son hits de memoria O(1) sin query BD. Al registrar una nueva versión vía API, `invalidate(templateName)` limpia ambas entradas.

### Generación PDF — Mustache + Flying Saucer (in-process)
Los templates son HTML XHTML-válido con variables Mustache almacenados en `data.document_templates.content`. `HtmlPdfDocumentGenerator` renderiza el HTML con `Mustache.execute(fields)` y lo convierte a PDF con `ITextRenderer` de Flying Saucer. El proceso es completamente in-process (~20 ms/doc) sin dependencias externas adicionales.

### Templates en BD (no en GCS)
El contenido HTML Mustache se almacena en la columna `content TEXT` de `data.document_templates`. Esto elimina un round-trip HTTP en cada cache miss, simplifica el setup local (solo PostgreSQL, sin fake-GCS para templates) y garantiza atomicidad: content + metadata se actualizan en una sola transacción.

### fixedDelay en lugar de cron
El scheduler usa `@Scheduled(fixedDelayString)`. El countdown comienza cuando el ciclo termina, evitando solapamientos si el lote tarda más que el intervalo configurado.

### Recovery de estados colgados
`recoverStuckProcessing()` se llama al inicio de cada ciclo. Resetea a `PENDING` los registros en `PROCESSING` cuyo `updated_at` supera el timeout configurado. Cubre crashes mid-processing sin intervención manual.

---

## Componentes principales

### Capa de eventos (Kafka)

| Clase | Responsabilidad |
|-------|----------------|
| `DocumentGenerationRequestedConsumer` | Consume el topic de entrada; verifica idempotencia y persiste como `PENDING` |
| `DocumentGenerationCompletedPublisher` | Publica el evento de resultado al topic de salida |

### Scheduler y servicio

| Clase | Responsabilidad |
|-------|----------------|
| `DocumentGenerationScheduler` | Orquesta el ciclo completo: recovery → claim → generate → upload → publish |
| `DocumentGenerationService` | Capa `@Transactional` para operaciones de estado de `RawData` |
| `TemplateCache` | Caché singleton (Mustache compilado + entidad BD) con invalidación por nombre |
| `HtmlPdfDocumentGenerator` | Renderiza HTML Mustache y convierte a PDF con Flying Saucer |

### Storage

| Clase | Responsabilidad |
|-------|----------------|
| `GcsStorageClient` | Cliente GCS: upload, download, list, delete, exists |
| `StorageService` | Capa de negocio sobre `GcsStorageClient` con validaciones |
| `TemplateService` | Orquesta registro de templates: guarda content en BD + invalida caché |

### API REST

| Clase | Responsabilidad |
|-------|----------------|
| `TemplateController` | CRUD de templates del catálogo (`/v1/templates`) |
| `StorageController` | Gestión genérica de archivos GCS (`/v1/storage/files`) |

---

## Referencias

- [Setup local](../06-development/local-setup.md)
- [Kafka — topics y esquemas de eventos](../05-infrastructure/kafka.md)
- [Base de datos — esquema y migraciones](../05-infrastructure/database.md)
- [Scheduler — ciclo de procesamiento](../03-batch/batch-job.md)
- [Templates — catálogo y variables Mustache](../02-domain/templates.md)
- [Templates API](../04-api/templates-api.md)
- [Storage API](../04-api/storage-api.md)
- [Estructura de paquetes](../06-development/package-structure.md)
- [ADR-001 — Decisión de arquitectura](decisions/ADR-001-generacion-asincrona-documentos.md)
- [ADR-002 — Migración de S3 a GCS](decisions/ADR-002-gcs-sobre-s3.md)

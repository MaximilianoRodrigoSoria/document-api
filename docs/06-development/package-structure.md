# Estructura de Paquetes — batch-document-generator

## Decisión de arquitectura

Este servicio **no utiliza arquitectura hexagonal**. Se adopta una estructura **por capa técnica**, directa y alineada con las convenciones estándar de Spring Boot + Spring Batch. El objetivo es mantener el código predecible, navegable y fácil de onboardear sin la ceremonia de puertos y adaptadores.

La separación de responsabilidades se logra a través de paquetes bien definidos, donde cada uno tiene una única razón de existir.

---

## Árbol de paquetes

```
cl.tenpo.batch.document.generator
│
├── BatchDocumentGeneratorApplication.java
│
├── config/
│   ├── KafkaConfig.java
│   ├── GcsConfig.java
│   └── DocumentGenerationProperties.java
│
├── controller/
│   ├── JobController.java
│   └── StorageController.java
│
├── consumer/
│   └── DocumentGenerationRequestedConsumer.java
│
├── publisher/
│   └── DocumentGenerationCompletedPublisher.java
│
├── batch/
│   ├── job/
│   │   ├── DocumentGenerationJobConfig.java
│   │   ├── GenerationStepConfig.java
│   │   └── PublicationStepConfig.java
│   ├── listener/
│   │   └── RecoveryJobListener.java
│   ├── reader/
│   │   ├── PendingDocumentReader.java
│   │   └── GeneratedDocumentReader.java
│   ├── processor/
│   │   └── DocumentGenerationProcessor.java
│   ├── writer/
│   │   ├── DocumentGenerationWriter.java
│   │   └── DocumentPublicationWriter.java
│   └── cache/
│       └── TemplateCache.java
│
├── generator/
│   ├── DocumentGenerator.java               <- interfaz
│   └── jasper/
│       └── JasperDocumentGenerator.java
│
├── storage/
│   └── GcsStorageClient.java
│
├── service/
│   └── StorageService.java
│
├── model/
│   ├── entity/
│   │   ├── RawData.java
│   │   └── DocumentTemplate.java
│   ├── event/
│   │   ├── DocumentGenerationRequestedEvent.java
│   │   └── DocumentGenerationCompletedEvent.java
│   ├── dto/
│   │   ├── FileMetadataDto.java
│   │   └── FileListResponse.java
│   └── enums/
│       ├── RawDataStatus.java
│       ├── DocumentTemplateStatus.java
│       └── DocumentDomain.java
│
├── repository/
│   ├── RawDataRepository.java
│   └── DocumentTemplateRepository.java
│
├── constants/
│   ├── BatchConstants.java
│   └── LogConstants.java
│
└── exception/
    ├── DocumentGenerationException.java
    ├── TemplateNotFoundException.java
    ├── TemplateDownloadException.java
    ├── IdempotencyConflictException.java
    └── advice/
        └── GlobalControllerAdvice.java
```

---

## Descripción de cada paquete

### `config/`

Clases de configuración de Spring. Cada clase configura un componente de infraestructura de forma aislada.

| Clase | Responsabilidad |
|-------|----------------|
| `KafkaConfig` | Factories de consumer y producer con serialización JSON, políticas de ack y reintentos |
| `GcsConfig` | Instancia el `Storage` client de GCS con soporte de emulator host para desarrollo local |
| `DocumentGenerationProperties` | `@ConfigurationProperties` para topics Kafka, chunk size, cron, publication limit, recovery timeout y thread pool |

---

### `controller/`

Endpoints HTTP expuestos por el servicio.

| Clase | Endpoint | Responsabilidad |
|-------|----------|----------------|
| `JobController` | `POST /v1/jobs/document-generation/run` | Lanza el Job manualmente fuera del horario programado |
| `StorageController` | `GET /v1/storage/files`, `GET /v1/storage/files/content?gcsKey=...`, etc. | Operaciones de exploración y descarga sobre el bucket GCS |

No exponen lógica de negocio. `StorageController` delega en `StorageService`.

---

### `consumer/`

Consumidores de Kafka. Cada clase escucha un único topic.

| Clase | Topic | Responsabilidad |
|-------|-------|----------------|
| `DocumentGenerationRequestedConsumer` | `document.generation.requested` | Recibe el evento, verifica idempotencia por `idempotency_id` y persiste como `PENDING`. Hace commit manual (`MANUAL_IMMEDIATE`) tras la persistencia exitosa. |

**Regla:** los consumers no contienen lógica de negocio más allá de la persistencia inicial. No generan documentos, no llaman a GCS, no publican eventos.

**Comportamiento de idempotencia:**

| Estado del registro existente | Acción |
|------------------------------|--------|
| No existe | INSERT `raw_data` con `status = PENDING` |
| Existe, `status != FAILED` | ACK sin persistir |
| Existe, `status = FAILED` | Re-insert como `PENDING` (política de reprocesamiento) |

---

### `publisher/`

Productores de Kafka. Encapsulan el envío de eventos hacia otros servicios.

| Clase | Topic | Responsabilidad |
|-------|-------|----------------|
| `DocumentGenerationCompletedPublisher` | `document.generation.completed` | Publica el evento de documento generado. Invocado por `DocumentPublicationWriter` dentro de la transacción Spring Batch. |

**Regla:** los publishers no deciden cuándo publicar ni qué publicar. Esa orquestación la realiza el Writer correspondiente.

---

### `batch/`

Corazón del procesamiento por lotes. Organizado por rol dentro del pipeline Spring Batch.

#### `batch/job/`

| Clase | Responsabilidad |
|-------|----------------|
| `DocumentGenerationJobConfig` | Define el `documentGenerationJob` con sus dos Steps y el `RecoveryJobListener`. |
| `GenerationStepConfig` | Configura el `generationStep`: Reader + Processor + Writer. Chunk 1000, fault tolerant, skip limit 10, pool 4-8 threads. |
| `PublicationStepConfig` | Configura el `publicationStep`: Reader + Writer. Chunk 25, single-threaded, límite 100 registros. |

#### `batch/listener/`

| Clase | Hook | Responsabilidad |
|-------|------|----------------|
| `RecoveryJobListener` | `beforeJob()` | Detecta registros `PROCESSING` colgados (> 30 min) y los resetea a `PENDING`. |

#### `batch/reader/`

| Clase | Tipo | Query |
|-------|------|-------|
| `PendingDocumentReader` | `JdbcPagingItemReader` | `WHERE status IN ('PENDING','ERROR') AND retry_count < 3 ORDER BY template_name FOR UPDATE SKIP LOCKED` |
| `GeneratedDocumentReader` | `JdbcPagingItemReader` | `WHERE status = 'GENERATED' ORDER BY processed_at ASC LIMIT 100` |

`PendingDocumentReader` usa `FOR UPDATE SKIP LOCKED` y `setSaveState(false)` para multi-threading seguro. `ORDER BY template_name` maximiza el hit rate del `TemplateCache`.

#### `batch/processor/`

| Clase | Responsabilidad |
|-------|----------------|
| `DocumentGenerationProcessor` | (1) marca `PROCESSING`, (2) obtiene `JasperReport` desde `TemplateCache.getReport()` — sin query BD ni deserialización en el hot path, (3) deserializa `data` (JSONB), (4) genera PDF con JasperReports, (5) sube a GCS, (6) retorna ítem con `status = GENERATED`. Captura excepciones internamente y retorna `status = ERROR` sin lanzar hacia Spring Batch. No inyecta `DocumentTemplateRepository` directamente; esa responsabilidad recae en `TemplateCache`. |

#### `batch/writer/`

| Clase | Responsabilidad |
|-------|----------------|
| `DocumentGenerationWriter` | Bulk UPDATE: éxito → `GENERATED` + `document_url`. Error con retry disponible → `ERROR` + `retry_count++`. Error sin retry → `FAILED`. |
| `DocumentPublicationWriter` | Publica evento Kafka + UPDATE `status = PUBLISHED`. Ambas en la misma transacción Spring Batch. |

#### `batch/cache/`

| Clase | Scope | Responsabilidad |
|-------|-------|----------------|
| `TemplateCache` | `@StepScope` | Caché con dos mapas internos: `entityCache` (`templateName → DocumentTemplate`) y `reportCache` (`templateName → JasperReport`). API principal: `getReport(templateName)` — en el primer acceso consulta BD, descarga bytes GCS y deserializa el `.jasper` con `JRLoader.loadObject()`; los accesos posteriores al mismo template retornan desde `ConcurrentHashMap`. Inyecta `DocumentTemplateRepository`, liberando al Processor de esta dependencia. Con 10 000 registros y 3 templates: 3 queries + 3 descargas + 3 deserializaciones en lugar de 10 000. El bean se destruye al finalizar el Step. |

---

### `generator/`

Abstracción de la generación física del documento.

| Clase | Tipo | Responsabilidad |
|-------|------|----------------|
| `DocumentGenerator` | Interfaz | Contrato: recibe `JasperReport` (ya deserializado, inmutable) + `Map<String, Object>` de campos y retorna `byte[]` del PDF |
| `jasper/JasperDocumentGenerator` | Implementación | Usa JasperReports. Recibe el `JasperReport` cacheado desde el Processor, lo llena con los campos (`fillReport` → `JasperPrint` independiente por thread) y exporta a PDF con compresión. No realiza `JRLoader.loadObject()` — esa operación quedó en `TemplateCache`. |

---

### `storage/`

Cliente de Google Cloud Storage. Abstrae la interacción con GCS.

| Clase | Responsabilidad |
|-------|----------------|
| `GcsStorageClient` | Sube documentos generados al bucket, descarga templates compilados, lista archivos y resuelve signed URLs. No conoce el concepto de template ni de documento. |

---

### `service/`

Servicios de aplicación que orquestan operaciones complejas sobre múltiples repositorios o clientes.

| Clase | Responsabilidad |
|-------|----------------|
| `StorageService` | Operaciones de exploración GCS para la API HTTP: listar archivos, obtener metadata, descargar contenido, generar signed URLs. Delega en `GcsStorageClient`. |

---

### `model/`

Clases de datos sin lógica de negocio.

#### `model/entity/`

| Clase | Tabla | Descripción |
|-------|-------|-------------|
| `RawData` | `batch.raw_data` | Ciclo de vida de cada solicitud: `idempotency_id`, `template_name`, `type`, `data` (JSONB), `status`, `retry_count`, `document_url`, `error_message`, timestamps |
| `DocumentTemplate` | `batch.document_templates` | Catálogo de templates: `name`, `domain`, `gcsKey`, `version`, `status` |

#### `model/event/`

| Clase | Dirección | Descripción |
|-------|-----------|-------------|
| `DocumentGenerationRequestedEvent` | Entrada | Payload del topic `document.generation.requested` |
| `DocumentGenerationCompletedEvent` | Salida | Payload del topic `document.generation.completed` |

#### `model/dto/`

| Clase | Descripción |
|-------|-------------|
| `FileMetadataDto` | Metadata de un archivo en GCS: name, bucket, size, contentType, md5, timestamps |
| `FileListResponse` | Respuesta paginada del endpoint de listado: items + nextPageToken |

#### `model/enums/`

| Enum | Valores |
|------|---------|
| `RawDataStatus` | `PENDING`, `PROCESSING`, `GENERATED`, `PUBLISHED`, `ERROR`, `FAILED` |
| `DocumentTemplateStatus` | `ACTIVE`, `INACTIVE`, `DEPRECATED` |
| `DocumentDomain` | `MISIONES`, `LOANS`, ... (se extiende al agregar dominios) |

---

### `constants/`

| Clase | Descripción |
|-------|-------------|
| `BatchConstants` | Nombres de Job, Steps, parámetros del Job, límite de reintentos |
| `LogConstants` | Claves MDC y prefijos de log estructurado |

---

### `repository/`

Interfaces de Spring Data JPA.

| Clase | Responsabilidad |
|-------|----------------|
| `RawDataRepository` | CRUD sobre `RawData`. Consultas por `status`, por `idempotency_id`, conteo pendientes y detección de PROCESSING colgados. |
| `DocumentTemplateRepository` | Consultas sobre `DocumentTemplate` por `name` y `status`. |

---

### `exception/`

| Clase | Descripción |
|-------|-------------|
| `DocumentGenerationException` | Error durante la generación física del documento (JasperReports) |
| `TemplateNotFoundException` | El template solicitado no existe en el catálogo |
| `TemplateDownloadException` | Error al descargar el template compilado desde GCS |
| `IdempotencyConflictException` | Ya existe una solicitud activa con el mismo `idempotency_id` |
| `advice/GlobalControllerAdvice` | `@RestControllerAdvice` que mapea excepciones a respuestas HTTP estructuradas |

---

## Reglas de dependencia

```
consumer / controller
        |
        v
  batch (job, reader, processor, writer, cache)
        |
        v
  generator / storage / service / publisher / repository
        |
        v
      model / constants
```

Ninguna capa puede importar desde una capa superior. `model` y `constants` no dependen de nadie. Los puntos de entrada (`consumer`, `controller`) solo orquestan — no contienen lógica de negocio.

**Lo que no se hace en este proyecto:**

- No hay arquitectura hexagonal (sin puertos ni adaptadores).
- No hay `usecase/` ni `application/` como paquetes intermedios.
- Los listeners/consumers no contienen lógica de negocio.
- Los publishers no deciden cuándo ni qué publicar.

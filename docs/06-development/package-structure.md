# Estructura de Paquetes — document-generator-api

## Decisión de arquitectura

Este servicio **no utiliza arquitectura hexagonal**. Se adopta una estructura **por capa técnica**, directa y alineada con las convenciones estándar de Spring Boot. El objetivo es mantener el código predecible, navegable y fácil de onboardear sin la ceremonia de puertos y adaptadores.

La separación de responsabilidades se logra a través de paquetes bien definidos, donde cada uno tiene una única razón de existir.

---

## Árbol de paquetes

```
ar.com.laboratory
│
├── Application.java
│
├── cache/
│   └── TemplateCache.java
│
├── common/model/
│   └── TestDTO.java
│
├── config/
│   ├── DocumentGenerationProperties.java
│   ├── GcsConfig.java
│   ├── KafkaConfig.java
│   ├── SwaggerConfig.java
│   ├── WebClientConfig.java
│   └── WebClientFilter.java
│
├── constants/
│   ├── AppConstants.java
│   ├── KafkaTopicConstants.java
│   └── LogConstants.java
│
├── consumer/
│   └── DocumentGenerationRequestedConsumer.java
│
├── controller/
│   ├── HealthController.java
│   ├── StorageController.java
│   └── TemplateController.java
│
├── exception/
│   ├── DocumentGenerationException.java
│   ├── IdempotencyConflictException.java
│   ├── StorageFileAlreadyExistsException.java
│   ├── StorageFileNotFoundException.java
│   ├── StorageInvalidFolderException.java
│   ├── TemplateDownloadException.java
│   ├── TemplateException.java
│   ├── TemplateNotFoundException.java
│   └── advice/
│       └── GlobalControllerAdvice.java
│
├── generator/
│   └── HtmlPdfDocumentGenerator.java
│
├── model/
│   ├── ResponseErrorDto.java
│   ├── dto/
│   │   ├── storage/
│   │   │   ├── FileListResponse.java
│   │   │   ├── FileMetadataResponse.java
│   │   │   └── FileUploadResponse.java
│   │   └── template/
│   │       ├── TemplateDetailResponse.java
│   │       ├── TemplateResponse.java
│   │       ├── TemplateStatusRequest.java
│   │       ├── TemplateUpdateRequest.java
│   │       └── TemplateUploadResponse.java
│   ├── entity/
│   │   ├── DocumentTemplate.java
│   │   └── RawData.java
│   ├── enums/
│   │   ├── DocumentDomain.java
│   │   ├── DocumentTemplateStatus.java
│   │   └── RawDataStatus.java
│   └── event/
│       ├── DocumentGenerationCompletedEvent.java
│       └── DocumentGenerationRequestedEvent.java
│
├── publisher/
│   └── DocumentGenerationCompletedPublisher.java
│
├── repository/
│   ├── DocumentTemplateRepository.java
│   └── RawDataRepository.java
│
├── scheduler/
│   └── DocumentGenerationScheduler.java
│
├── service/
│   ├── DocumentGenerationService.java
│   ├── StorageService.java
│   └── TemplateService.java
│
├── storage/
│   └── GcsStorageClient.java
│
└── util/
    └── ObjectMapperFactory.java
```

---

## Descripción de cada paquete

### `cache/`

Cache singleton del ciclo de vida del scheduler.

| Clase | Responsabilidad |
|-------|----------------|
| `TemplateCache` | Cache `@Component` singleton con dos `ConcurrentHashMap`: `entityCache` (metadata BD) y `compiledCache` (template Mustache compilado). Primer acceso por `templateName` consulta BD y compila el Mustache; siguientes son hits O(1). `invalidate(name)` limpia ambas entradas al registrar nueva versión via API. |

---

### `config/`

Clases de configuración de Spring. Cada clase configura un componente de infraestructura de forma aislada.

| Clase | Responsabilidad |
|-------|----------------|
| `KafkaConfig` | Factories de consumer y producer con serialización JSON, políticas de ack y reintentos |
| `GcsConfig` | Instancia el `Storage` client de GCS con soporte de emulator host para desarrollo local |
| `DocumentGenerationProperties` | `@ConfigurationProperties` para topics Kafka, batch-size, fixed-delay y recovery timeout |
| `SwaggerConfig` | Configuración de OpenAPI / Swagger UI |
| `WebClientConfig` | Instancia el `WebClient` de Spring |
| `WebClientFilter` | Filtro HTTP para logging de requests/responses |

---

### `constants/`

| Clase | Descripción |
|-------|-------------|
| `AppConstants` | Constantes generales de la aplicación |
| `KafkaTopicConstants` | Nombres de topics Kafka |
| `LogConstants` | Claves MDC y prefijos de log estructurado |

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

### `controller/`

Endpoints HTTP expuestos por el servicio.

| Clase | Endpoint | Responsabilidad |
|-------|----------|----------------|
| `HealthController` | `GET /actuator/health` | Estado del servicio |
| `TemplateController` | `/v1/templates` | CRUD de templates del catálogo |
| `StorageController` | `/v1/storage/files` | Operaciones de exploración y descarga sobre el bucket GCS |

No exponen lógica de negocio. `StorageController` delega en `StorageService`. `TemplateController` delega en `TemplateService`.

---

### `exception/`

| Clase | Descripción |
|-------|-------------|
| `DocumentGenerationException` | Error durante la generación física del documento (Mustache / Flying Saucer) |
| `TemplateNotFoundException` | El template solicitado no existe en el catálogo |
| `TemplateDownloadException` | Error al acceder al template en la base de datos |
| `TemplateException` | Error genérico de gestión de templates |
| `IdempotencyConflictException` | Ya existe una solicitud activa con el mismo `idempotency_id` |
| `StorageFileAlreadyExistsException` | El archivo ya existe en GCS |
| `StorageFileNotFoundException` | El archivo no existe en GCS |
| `StorageInvalidFolderException` | Carpeta GCS inválida |
| `advice/GlobalControllerAdvice` | `@RestControllerAdvice` que mapea excepciones a respuestas HTTP estructuradas |

---

### `generator/`

Generación física del documento PDF.

| Clase | Responsabilidad |
|-------|----------------|
| `HtmlPdfDocumentGenerator` | Renderiza un template Mustache compilado con los campos del registro (`Map<String, Object>`) y convierte el HTML resultante a PDF con Flying Saucer `ITextRenderer`. Retorna `byte[]` del PDF. (~20 ms/doc en proceso, sin dependencias externas). |

---

### `publisher/`

Productores de Kafka. Encapsulan el envío de eventos hacia otros servicios.

| Clase | Topic | Responsabilidad |
|-------|-------|----------------|
| `DocumentGenerationCompletedPublisher` | `document.generation.completed` | Publica el evento de documento generado. Invocado por `DocumentGenerationScheduler` tras `markGenerated()`. |

**Regla:** los publishers no deciden cuándo publicar ni qué publicar. Esa orquestación la realiza el scheduler.

---

### `scheduler/`

Corazón del pipeline de generación de documentos.

| Clase | Responsabilidad |
|-------|----------------|
| `DocumentGenerationScheduler` | Orquesta el ciclo completo con `@Scheduled(fixedDelayString)`: (1) `recoverStuckProcessing()`, (2) `claimPendingRecords(batchSize)`, (3) por cada registro: obtiene template compilado desde `TemplateCache`, deserializa `data` JSONB, genera PDF con `HtmlPdfDocumentGenerator`, sube a GCS con `GcsStorageClient`, `markGenerated()`, publica evento Kafka, `markPublished()`. Ante error: `markError()`. Single-threaded por diseño. |

---

### `service/`

Servicios de aplicación que orquestan operaciones con lógica de negocio.

| Clase | Responsabilidad |
|-------|----------------|
| `DocumentGenerationService` | Capa `@Transactional` para operaciones de estado de `RawData`: `recoverStuckProcessing()`, `claimPendingRecords()`, `markGenerated()`, `markPublished()`, `markError()` |
| `StorageService` | Operaciones de exploración GCS para la API HTTP: listar archivos, obtener metadata, descargar contenido. Delega en `GcsStorageClient`. |
| `TemplateService` | Orquesta registro de templates: guarda `content` HTML en `data.document_templates` e invalida `TemplateCache`. |

---

### `storage/`

Cliente de Google Cloud Storage. Abstrae la interacción con GCS.

| Clase | Responsabilidad |
|-------|----------------|
| `GcsStorageClient` | Sube documentos generados al bucket, descarga archivos, lista objetos, verifica existencia y elimina. No conoce el concepto de template ni de documento. |

---

### `model/`

Clases de datos sin lógica de negocio.

#### `model/entity/`

| Clase | Tabla | Descripción |
|-------|-------|-------------|
| `RawData` | `data.raw_data` | Ciclo de vida de cada solicitud: `idempotency_id`, `template_name`, `type`, `data` (JSONB), `status`, `retry_count`, `document_url`, `error_message`, timestamps |
| `DocumentTemplate` | `data.document_templates` | Catálogo de templates: `name`, `domain`, `content` (HTML Mustache), `version`, `status` |

#### `model/event/`

| Clase | Dirección | Descripción |
|-------|-----------|-------------|
| `DocumentGenerationRequestedEvent` | Entrada | Payload del topic `document.generation.requested` |
| `DocumentGenerationCompletedEvent` | Salida | Payload del topic `document.generation.completed` |

#### `model/dto/`

| Clase | Descripción |
|-------|-------------|
| `storage/FileListResponse` | Respuesta paginada del endpoint de listado: items + nextPageToken |
| `storage/FileMetadataResponse` | Metadata de un archivo en GCS: name, bucket, size, contentType, md5, timestamps |
| `storage/FileUploadResponse` | Respuesta tras subir un archivo |
| `template/TemplateDetailResponse` | Detalle completo de un template incluyendo contenido HTML |
| `template/TemplateResponse` | Resumen de template sin contenido |
| `template/TemplateStatusRequest` | Request para cambiar el status de un template |
| `template/TemplateUpdateRequest` | Request para actualizar un template |
| `template/TemplateUploadResponse` | Respuesta tras registrar un template |

#### `model/enums/`

| Enum | Valores |
|------|---------|
| `RawDataStatus` | `PENDING`, `PROCESSING`, `GENERATED`, `PUBLISHED`, `ERROR`, `FAILED` |
| `DocumentTemplateStatus` | `ACTIVE`, `INACTIVE`, `DEPRECATED` |
| `DocumentDomain` | `MISIONES`, `LOANS`, ... (se extiende al agregar dominios) |

---

### `repository/`

Interfaces de Spring Data JPA.

| Clase | Responsabilidad |
|-------|----------------|
| `RawDataRepository` | CRUD sobre `RawData`. Consultas por `status`, por `idempotency_id`, claim con `FOR UPDATE SKIP LOCKED` y detección de PROCESSING colgados. |
| `DocumentTemplateRepository` | Consultas sobre `DocumentTemplate` por `name` y `status`. |

---

### `util/`

| Clase | Descripción |
|-------|-------------|
| `ObjectMapperFactory` | Factory para instanciar `ObjectMapper` con configuración estándar. |

---

## Reglas de dependencia

```
consumer / controller
        |
        v
  scheduler / service
        |
        v
  generator / storage / cache / publisher / repository
        |
        v
      model / constants / util
```

Ninguna capa puede importar desde una capa superior. `model`, `constants` y `util` no dependen de nadie. Los puntos de entrada (`consumer`, `controller`) solo orquestan — no contienen lógica de negocio.

**Lo que no se hace en este proyecto:**

- No hay arquitectura hexagonal (sin puertos ni adaptadores).
- No hay `usecase/` ni `application/` como paquetes intermedios.
- Los listeners/consumers no contienen lógica de negocio.
- Los publishers no deciden cuándo ni qué publicar.
- No hay Spring Batch (sin Steps, Readers, Processors, Writers, Jobs).

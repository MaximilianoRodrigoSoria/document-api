# document-generator-api — Visión general

Microservicio de generación asíncrona de documentos PDF para el ecosistema Tenpo.

## Propósito

Desacoplar la generación física de documentos del flujo transaccional de los servicios de negocio. Un servicio como `payment-loyalty` publica una solicitud en Kafka y `document-generator-api` se encarga de todo el pipeline posterior: persistencia, generación PDF (AcroForm + PDFBox), almacenamiento en GCS y notificación del resultado.

## Contexto de negocio

El dominio inicial es **Misiones**: cuando se crea una misión en `payment-loyalty`, se genera un documento PDF asociado. El servicio está diseñado para escalar a otros dominios sin modificar el núcleo del pipeline — alcanza con registrar un nuevo template via `POST /v1/templates`.

## Stack tecnológico

| Capa | Tecnología |
|------|-----------|
| Runtime | Java 17 · Spring Boot 3.x |
| Generación PDF | Apache PDFBox (AcroForm fill + flatten) |
| Persistencia | PostgreSQL 15.2 · Spring Data JPA · Flyway |
| Mensajería | Apache Kafka (KRaft, sin Zookeeper) |
| Almacenamiento | Google Cloud Storage · fake-gcs-server (local) |
| Observabilidad | Micrometer · MDC estructurado |

## Flujo resumido

```
payment-loyalty
    └─► Kafka [document.generation.requested]
            └─► DocumentGenerationRequestedConsumer
                    └─► data.raw_data (status = PENDING)
                            └─► @Scheduled cron → DocumentGenerationScheduler
                                    ├─► TemplateCache → PDFBox → GCS
                                    └─► Kafka [document.generation.completed]
                                                └─► payment-loyalty (document_url)
```

## Documentación por sección

| Sección | Contenido |
|---------|-----------|
| [architecture.md](architecture.md) | Flujo completo, componentes, fronteras transaccionales y decisiones técnicas |
| [decisions/](decisions/) | ADRs — decisiones arquitectónicas con contexto y trade-offs |
| [02 · Dominio](../02-domain/) | Eventos Kafka, máquina de estados, gestión de templates |
| [03 · Scheduler](../03-batch/) | Ciclo de procesamiento, recovery y tuning |
| [04 · API REST](../04-api/) | Templates API, Storage API y códigos de error |
| [05 · Infraestructura](../05-infrastructure/) | Base de datos, Kafka, GCS y observabilidad |
| [06 · Desarrollo](../06-development/) | Setup local, estructura de paquetes y estrategia de tests |
| [07 · Operaciones](../07-operations/) | Runbook, gestión de templates en producción |

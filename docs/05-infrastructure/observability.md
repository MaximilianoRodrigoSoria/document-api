# Observabilidad — Logs, MDC y metricas

## Logs estructurados con MDC

El servicio enriquece el contexto de log con Mapped Diagnostic Context (MDC). Cada log emitido durante el procesamiento de un item incluye automaticamente:

| Key MDC | Valor | Donde se setea |
|---------|-------|----------------|
| `processId` | `raw_data.process_id` | `DocumentGenerationProcessor` |
| `idempotencyId` | `raw_data.idempotency_id` | `DocumentGenerationProcessor` |
| `templateName` | `raw_data.template_name` | `DocumentGenerationProcessor` |

El MDC se limpia en el bloque `finally` del Processor para no contaminar otros threads del pool.

### Ejemplo de log con MDC

```
2026-05-08 15:30:00.123 DEBUG [generation-5] [processId=1042,idempotencyId=550e8400,templateName=compra-y-gana-v1]
    c.t.b.d.g.batch.processor.DocumentGenerationProcessor - [PROC_START] Processing record: processId=1042, template=compra-y-gana-v1
```

---

## Constantes de log (LogConstants)

Todas las claves de log estan centralizadas en `LogConstants`. Nunca se usan strings literales en codigo de produccion.

| Constante | Valor | Donde se usa |
|-----------|-------|--------------|
| `LOG_CONSUMER_RECEIVED` | `KAFKA_RECEIVED` | Consumer al recibir evento |
| `LOG_CONSUMER_IDEMPOTENT_SKIP` | `KAFKA_IDEMPOTENT_SKIP` | Consumer — evento duplicado |
| `LOG_PROCESSOR_START` | `PROC_START` | Processor al iniciar item |
| `LOG_PROCESSOR_PDF_GENERATED` | `PROC_PDF_OK` | Processor tras generar PDF |
| `LOG_PROCESSOR_S3_UPLOADED` | `PROC_GCS_OK` | Processor tras upload a GCS |
| `LOG_PROCESSOR_ERROR` | `PROC_ERROR` | Processor en catch |
| `LOG_WRITER_GENERATED` | `WRITE_GENERATED` | Writer — exito |
| `LOG_WRITER_ERROR` | `WRITE_ERROR` | Writer — fallo recuperable |
| `LOG_WRITER_FAILED` | `WRITE_FAILED` | Writer — fallo permanente (FAILED) |
| `LOG_RECOVERY_RESET` | `RECOVERY_RESET` | RecoveryListener |

---

## Niveles de log recomendados por ambiente

| Nivel | Paquete | Ambiente |
|-------|---------|----------|
| `DEBUG` | `cl.tenpo.batch.document.generator` | Local / Dev |
| `INFO` | `cl.tenpo.batch.document.generator` | UAT / Prod |
| `WARN` | `org.springframework.batch` | Todos |
| `INFO` | `com.google.cloud` | UAT / Prod |

### Configuracion en YAML

```yaml
logging:
  level:
    cl.tenpo.batch.document.generator: DEBUG   # local
    org.springframework.batch: WARN
    com.zaxxer.hikari: INFO
```

---

## Actuator endpoints

| Endpoint | Descripcion |
|----------|-------------|
| `/actuator/health` | Estado del servicio (DB, Kafka, GCS) |
| `/actuator/metrics` | Metricas Micrometer |
| `/actuator/info` | Version del servicio |

---

## Metricas clave a monitorear

| Metrica | Tipo | Descripcion |
|---------|------|-------------|
| `batch.job.active` | Gauge | Jobs activos en ejecucion |
| `batch.job.completed` | Counter | Jobs completados |
| `batch.step.active` | Gauge | Steps activos |
| `batch.item.read` | Counter | Items leidos por Step |
| `batch.item.write` | Counter | Items escritos por Step |
| `hikaricp.connections.active` | Gauge | Conexiones DB activas |
| `kafka.consumer.fetch-rate` | Gauge | Rate de consumo Kafka |

---

## New Relic

El servicio usa el appender `com.newrelic.logging:logback` para enviar logs estructurados a New Relic. Configurado via `logback-spring.xml`.

Los atributos MDC (`processId`, `idempotencyId`, `templateName`) son capturados automaticamente como atributos del log en New Relic, permitiendo busqueda y correlacion por item.

# Observabilidad — Logs, MDC y metricas

## Logs estructurados con MDC

El servicio enriquece el contexto de log con Mapped Diagnostic Context (MDC). Cada log emitido durante el procesamiento de un item incluye automaticamente:

| Key MDC | Valor | Donde se setea |
|---------|-------|----------------|
| `processId` | `raw_data.process_id` | `DocumentGenerationScheduler` |
| `idempotencyId` | `raw_data.idempotency_id` | `DocumentGenerationScheduler` |
| `templateName` | `raw_data.template_name` | `DocumentGenerationScheduler` |

El MDC se limpia en el bloque `finally` del metodo `processOne()` del scheduler para no contaminar el siguiente registro del mismo ciclo.

### Ejemplo de log con MDC

```
2026-05-08 15:30:00.123 DEBUG [scheduling-1] [processId=1042,idempotencyId=550e8400,templateName=compra-y-gana-v1]
    ar.com.laboratory.scheduler.DocumentGenerationScheduler - [PROC_START] Processing record: processId=1042, template=compra-y-gana-v1
```

---

## Constantes de log (LogConstants)

Todas las claves de log estan centralizadas en `LogConstants`. Nunca se usan strings literales en codigo de produccion.

| Constante | Valor | Donde se usa |
|-----------|-------|--------------|
| `LOG_CONSUMER_RECEIVED` | `KAFKA_RECEIVED` | Consumer al recibir evento |
| `LOG_CONSUMER_IDEMPOTENT_SKIP` | `KAFKA_IDEMPOTENT_SKIP` | Consumer — evento duplicado |
| `LOG_PROCESSOR_START` | `PROC_START` | Scheduler al iniciar procesamiento de un item |
| `LOG_PROCESSOR_PDF_GENERATED` | `PROC_PDF_OK` | Scheduler tras generar PDF |
| `LOG_PROCESSOR_GCS_UPLOADED` | `PROC_GCS_OK` | Scheduler tras upload a GCS |
| `LOG_PROCESSOR_ERROR` | `PROC_ERROR` | Scheduler en catch |
| `LOG_WRITER_GENERATED` | `WRITE_GENERATED` | Scheduler — markGenerated exitoso |
| `LOG_WRITER_ERROR` | `WRITE_ERROR` | Scheduler — markError (fallo recuperable) |
| `LOG_WRITER_FAILED` | `WRITE_FAILED` | Scheduler — markError (fallo permanente FAILED) |
| `LOG_RECOVERY_RESET` | `RECOVERY_RESET` | recoverStuckProcessing() |

---

## Niveles de log recomendados por ambiente

| Nivel | Paquete | Ambiente |
|-------|---------|----------|
| `DEBUG` | `ar.com.laboratory` | Local / Dev |
| `INFO` | `ar.com.laboratory` | UAT / Prod |
| `INFO` | `com.google.cloud` | UAT / Prod |

### Configuracion en YAML

```yaml
logging:
  level:
    ar.com.laboratory: DEBUG   # local
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
| `scheduler.cycles.total` | Counter | Ciclos del scheduler ejecutados |
| `scheduler.records.processed` | Counter | Registros procesados por ciclo |
| `scheduler.records.failed` | Counter | Registros que pasaron a ERROR/FAILED |
| `hikaricp.connections.active` | Gauge | Conexiones DB activas |
| `kafka.consumer.fetch-rate` | Gauge | Rate de consumo Kafka |

---

## Logging estructurado

El servicio usa Logback con formato JSON estructurado. Los atributos MDC (`processId`, `idempotencyId`, `templateName`) aparecen como campos del JSON en cada linea de log, permitiendo filtrado y correlacion en cualquier sistema de observabilidad que consuma los logs del contenedor (stdout/stderr).

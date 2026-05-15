# Scheduler de generación de documentos

## Resumen

`DocumentGenerationScheduler` orquesta el ciclo completo de generación de PDFs. En cada ejecución:

1. Recupera registros "colgados" (`PROCESSING` expirado → `PENDING`)
2. Reclama hasta `batch-size` registros candidatos (`PENDING`/`ERROR`)
3. Por cada registro: renderiza el PDF con Mustache + Flying Saucer, lo sube a GCS y publica el evento Kafka
4. Ante cualquier error, persiste `ERROR` o `FAILED` según el `retry_count`

```
@Scheduled(fixedDelayString = "${document-generation.schedule.fixed-delay}")
    │
    ├─ TX1: recoverStuckProcessing()
    │       UPDATE PROCESSING → PENDING (updated_at > timeout)
    │
    ├─ TX2: claimPendingRecords(batchSize)
    │       SELECT PENDING/ERROR FOR UPDATE SKIP LOCKED → UPDATE PROCESSING
    │
    └─ for each record:
           templateCache.getCompiledTemplate(templateName)  ← O(1) tras 1er acceso
           deserializar data (JSONB) → Map<String,Object>
           htmlPdfDocumentGenerator.generate(compiled, fields)
               └─ Mustache render → HTML string
               └─ Flying Saucer → PDF bytes (~20 ms)
           gcsStorageClient.uploadDocument(...)
           TX3: markGenerated(record, documentUrl)
           publisher.publish(topic, event)
           TX4: markPublished(record)
           ─ si error →
           TX5: markError(record, message)
```

---

## Fronteras transaccionales

Las operaciones de I/O externo (GCS, Kafka) ocurren **fuera de cualquier transacción** de BD. Cada actualización de estado es una TX independiente en `DocumentGenerationService`. Esto evita mantener conexiones abiertas durante llamadas de red.

| TX | Operación | Responsable |
|----|-----------|-------------|
| TX1 | bulk UPDATE PROCESSING → PENDING | `recoverStuckProcessing()` |
| TX2 | SELECT FOR UPDATE SKIP LOCKED → UPDATE PROCESSING | `claimPendingRecords()` |
| TX3 | UPDATE GENERATED + document_url + processed_at | `markGenerated()` |
| TX4 | UPDATE PUBLISHED + published_at | `markPublished()` |
| TX5 | UPDATE ERROR/FAILED + retry_count + error_message | `markError()` |

---

## TemplateCache

`TemplateCache` es un `@Component` singleton que persiste entre ciclos del scheduler. Mantiene dos `ConcurrentHashMap`:

- `entityCache` — metadata BD (`DocumentTemplate`)
- `compiledCache` — template Mustache compilado (`Mustache`)

El primer acceso por `templateName`:

1. Busca la entidad `DocumentTemplate` activa en BD (`findByNameAndStatus`)
2. Lee el contenido HTML desde `entity.getContent()` (sin llamadas externas)
3. Compila el HTML con `MustacheFactory.compile()` y almacena en `compiledCache`

Los accesos siguientes son hits de memoria O(1).

```
Con 10 000 registros y 3 templates distintos:
  3 queries a BD + 0 descargas GCS (primer ciclo)
  0 queries BD + 0 descargas GCS (ciclos posteriores)
```

Cuando se sube una nueva versión vía `POST /v1/templates`, `templateCache.invalidate(name)` limpia ambas entradas para forzar recompilación en el próximo ciclo.

---

## Generación PDF — Mustache + Flying Saucer

`HtmlPdfDocumentGenerator` renderiza el PDF en dos pasos en-proceso (~20 ms/documento):

**Paso 1 — Render HTML:**
```java
StringWriter writer = new StringWriter();
compiledTemplate.execute(writer, fields).flush();
String html = writer.toString();
```

El mapa `fields` proviene de deserializar el campo `data` (JSONB) del registro `raw_data`. Puede contener strings simples para `{{variable}}` y listas de mapas para `{{#lista}}...{{/lista}}`.

**Paso 2 — Conversión a PDF:**
```java
ITextRenderer renderer = new ITextRenderer();
renderer.setDocumentFromString(html);
renderer.layout();
renderer.createPDF(baos);
```

Flying Saucer convierte el HTML renderizado a PDF sin llamadas externas. El template debe ser XHTML-valid y usar solo CSS2.

---

## fixedDelay vs cron

El scheduler usa `@Scheduled(fixedDelayString)` en lugar de `@Scheduled(cron)`. La diferencia clave es que el countdown del `fixedDelay` **comienza cuando el ciclo termina**, lo que evita solapamientos si el procesamiento de un lote dura más que el intervalo configurado.

| Propiedad | `fixedDelay` | `cron` |
|-----------|-------------|--------|
| Countdown | Desde fin del ciclo anterior | Absoluto (reloj) |
| Solapamiento posible | No | Sí (si el ciclo dura más que el intervalo) |

---

## Recovery de registros colgados

Si el servicio se reinicia mientras hay registros en `PROCESSING`, esos registros quedan colgados. `recoverStuckProcessing()` los detecta al inicio de cada ciclo:

```sql
SELECT * FROM data.raw_data
WHERE status = 'PROCESSING'
  AND updated_at < now() - INTERVAL '{timeoutMinutes} minutes'
```

Y los resetea a `PENDING`. El timeout se configura en:

```yaml
document-generation:
  schedule:
    recovery-timeout-minutes: 30   # producción
    # recovery-timeout-minutes: 5  # local (application-local.yml)
```

---

## Reintentos y estados de error

| Escenario | Resultado |
|-----------|-----------|
| Error en generación (1er intento) | `status = ERROR`, `retry_count = 1` |
| Error en generación (2do intento) | `status = ERROR`, `retry_count = 2` |
| Error en generación (3er intento) | `status = FAILED`, no se reintenta |
| Crash mid-processing | `status = PROCESSING` colgado → recovery → `PENDING` |
| Template no encontrado en BD | `TemplateNotFoundException` → `ERROR` |
| HTML inválido para Flying Saucer | `DocumentGenerationException` → `ERROR` |
| Campo JSONB inválido | `DocumentGenerationException` → `ERROR` |

Los registros `FAILED` no son recogidos por `claimPendingRecords`. Requieren intervención manual (ver [runbook.md](../07-operations/runbook.md)).

---

## Configuración

```yaml
document-generation:
  schedule:
    fixed-delay: 5000               # ms entre fin de ciclo e inicio del siguiente
    batch-size: 500                 # registros por ciclo
    recovery-timeout-minutes: 30

  kafka:
    consumer-group-id: batch-document-generator
    topics:
      requested: document.generation.requested
      completed: document.generation.completed
```

Configuración local (`application-local.yml`):

```yaml
document-generation:
  schedule:
    fixed-delay: 5000
    batch-size: 20
    recovery-timeout-minutes: 5
```

---

## Thread-safety

El scheduler es **single-threaded** por diseño. `fixedDelay` garantiza que no haya dos ejecuciones simultáneas del mismo scheduler.

`TemplateCache` usa `ConcurrentHashMap.computeIfAbsent`, que garantiza compilación única por template incluso si en el futuro se añade paralelismo.

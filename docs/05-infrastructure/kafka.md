# Kafka — Topics y esquemas de eventos

## Topics

| Topic | Dirección | Descripción |
|-------|-----------|-------------|
| `document.generation.requested` | Entrada (consumer) | Solicitudes de generación recibidas de otros servicios |
| `document.generation.completed` | Salida (producer) | Notificación de documento generado y URI GCS disponible |

Los nombres son configurables mediante `document-generation.kafka.topic.requested` y `document-generation.kafka.topic.completed`.

---

## Evento de entrada — `DocumentGenerationRequestedEvent`

Publicado por el servicio productor al topic `document.generation.requested`. El consumer lo recibe, verifica idempotencia y persiste como `PENDING`.

```json
{
  "idempotencyId": "550e8400-e29b-41d4-a716-446655440000",
  "domain":         "loans",
  "templateName":   "contract-v1",
  "fields": {
    "clientName": "Juan Pérez",
    "amount":     "1000000",
    "date":       "2026-05-05"
  }
}
```

| Campo | Tipo | Requerido | Descripción |
|-------|------|-----------|-------------|
| `idempotencyId` | `String` | Sí | UUID único por solicitud. Se persiste como `idempotency_id` en `batch.raw_data`. Constraint `UNIQUE`. |
| `domain` | `String` | Sí | Dominio de negocio que origina la solicitud (ej. `loans`, `misiones`). Se mapea a la columna `type`. |
| `templateName` | `String` | Sí | Nombre del template a renderizar. Se persiste en `template_name` para indexación y cache. |
| `fields` | `Map<String, String>` | No | Variables dinámicas a inyectar en el template. Se persiste serializado en la columna `data` (JSONB). |

**Clave de partición**: `idempotencyId`

### Comportamiento del consumer ante duplicados

| Estado actual del registro | Acción |
|---------------------------|--------|
| No existe | INSERT `raw_data` con `status = PENDING` |
| Existe y `status != FAILED` | ACK sin persistir (idempotencia OK) |
| Existe y `status = FAILED` | Re-insert como `PENDING` (política de reprocesamiento) |

---

## Evento de salida — `DocumentGenerationCompletedEvent`

Publicado por el `DocumentPublicationWriter` en el Step 2 del Job, al topic `document.generation.completed`. Se publica dentro de la misma transacción Spring Batch que actualiza `status = PUBLISHED`.

```json
{
  "idempotencyId": "550e8400-e29b-41d4-a716-446655440000",
  "domain":        "loans",
  "templateName":  "contract-v1",
  "documentUrl":   "gs://batch-document-generator/documents/loans/550e8400-e29b-41d4-a716-446655440000.pdf",
  "status":        "GENERATED",
  "processedAt":   "2026-05-06T12:30:00Z"
}
```

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `idempotencyId` | `String` | Mismo ID de la solicitud original. Permite al consumidor correlacionar la respuesta. |
| `domain` | `String` | Dominio de negocio (mapeado desde la columna `type` de `raw_data`). |
| `templateName` | `String` | Template utilizado para la generación. |
| `documentUrl` | `String` | URI GCS del PDF generado (`gs://bucket/folder/file.pdf`). Siempre presente en este evento. |
| `status` | `String` | Estado de generación: siempre `GENERATED` en este evento. |
| `processedAt` | `String` | Timestamp ISO-8601 UTC en que el PDF fue generado y subido a GCS. |

**Clave de partición**: `idempotencyId`

**Nota:** si el `UPDATE` post-publish falla, Spring Batch hace rollback del chunk y reintenta. El consumidor del evento debe manejar duplicados usando `idempotencyId`.

---

## Consumer — configuración

| Propiedad | Valor |
|-----------|-------|
| `document-generation.kafka.consumer-group-id` | `batch-document-generator` |
| `auto-offset-reset` | `earliest` |
| `enable-auto-commit` | `false` |
| Modo de commit | `MANUAL_IMMEDIATE` (commit explícito tras persistencia exitosa) |

---

## Producer — configuración

| Propiedad | Valor | Propósito |
|-----------|-------|-----------|
| `acks` | `all` | Confirmación de todos los réplicas del broker |
| `retries` | `3` | Reintentos automáticos ante fallo transitorio |
| `enable.idempotence` | `true` | Garantiza exactly-once desde el producer |

---

## Modo de Kafka

El servicio usa **KRaft** (sin Zookeeper). La configuración local levanta un broker único con `docker-compose`. En producción se espera un cluster con múltiples brokers y particiones configuradas por topic.

Para detalles del setup local, ver [local-setup.md](../06-development/local-setup.md).

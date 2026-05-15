# Eventos Kafka — Contratos y ciclo de vida

## Topics

| Topic | Rol | Descripcion |
|-------|-----|-------------|
| `document.generation.requested` | Entrada (consumer) | Solicitudes de generacion enviadas por servicios externos |
| `document.generation.completed` | Salida (producer) | Notificacion de documento generado con URL en GCS |

Los nombres son configurables via `document.generation.kafka.topic.requested` y `document.generation.kafka.topic.completed`.

---

## Evento de entrada — DocumentGenerationRequestedEvent

Publicado por el servicio productor. El consumer verifica idempotencia y persiste como `PENDING`.

**Topic:** `document.generation.requested`
**Clave de particion:** `idempotencyId`

```json
{
  "idempotencyId": "550e8400-e29b-41d4-a716-446655440000",
  "domain":         "misiones",
  "templateName":   "mission-template-v1",
  "fields": {
    "missionName": "Compra en comercios adheridos",
    "startDate":   "2026-05-05",
    "endDate":     "2026-06-05",
    "conditions":  "Aplica para clientes segmentados"
  }
}
```

### Campos

| Campo | Tipo | Requerido | Descripcion |
|-------|------|-----------|-------------|
| `idempotencyId` | `String` (UUID) | Si | Clave unica por solicitud. Constraint UNIQUE en DB. Previene duplicados. |
| `domain` | `String` | Si | Dominio de negocio (ej. `misiones`, `loans`). Se persiste en la columna `type`. |
| `templateName` | `String` | Si | Nombre del template a renderizar. Debe existir en `data.document_templates` con status `ACTIVE`. |
| `fields` | `Map<String, Object>` | No | Variables dinamicas del template. Se persiste serializado como JSONB en la columna `data`. |

### Comportamiento ante duplicados

| Estado del registro existente | Accion del consumer |
|-------------------------------|---------------------|
| No existe | INSERT con `status = PENDING`, `retry_count = 0` |
| Existe, `status != FAILED` | ACK sin persistir (idempotencia OK) |
| Existe, `status = FAILED` | Re-insert como `PENDING` (politica de reprocesamiento) |

---

## Evento de salida — DocumentGenerationCompletedEvent

Publicado por `DocumentGenerationScheduler` tras `markPublished()`, que actualiza `status = PUBLISHED`.

**Topic:** `document.generation.completed`
**Clave de particion:** `idempotencyId`

```json
{
  "idempotencyId": "550e8400-e29b-41d4-a716-446655440000",
  "domain":        "misiones",
  "templateName":  "mission-template-v1",
  "documentUrl":   "gs://document-generator-bucket/misiones/550e8400-e29b-41d4-a716-446655440000.pdf",
  "status":        "GENERATED",
  "processedAt":   "2026-05-06T12:30:00"
}
```

### Campos

| Campo | Tipo | Descripcion |
|-------|------|-------------|
| `idempotencyId` | `String` | Mismo ID de la solicitud original. |
| `domain` | `String` | Dominio de negocio. |
| `templateName` | `String` | Template utilizado. |
| `documentUrl` | `String` | URI GCS del PDF generado. Formato: `gs://bucket/domain/idempotencyId.pdf` |
| `status` | `String` | Siempre `GENERATED` en este evento. |
| `processedAt` | `LocalDateTime` | Timestamp de generacion del PDF. |

### Garantias de entrega

- El evento se publica antes de que el scheduler invoque `markPublished()`.
- Si el `markPublished()` falla despues del publish, el registro queda en `GENERATED` y el scheduler lo publica nuevamente en el proximo ciclo.
- **Los consumidores deben ser idempotentes** usando `idempotencyId` como clave de deduplicacion.

---

## Configuracion del consumer

| Propiedad | Valor |
|-----------|-------|
| `consumer-group-id` | `document-generator-api` |
| `auto-offset-reset` | `earliest` |
| `enable-auto-commit` | `false` |
| Modo de commit | `MANUAL_IMMEDIATE` (commit explicitamente tras persistencia exitosa) |

## Configuracion del producer

| Propiedad | Valor | Proposito |
|-----------|-------|-----------|
| `acks` | `all` | Confirmacion de todos los replicas del broker |
| `retries` | `3` | Reintentos ante fallo transitorio |
| `enable.idempotence` | `true` | Exactly-once desde el producer |

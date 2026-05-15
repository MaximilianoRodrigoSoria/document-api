# Maquina de estados — raw_data.status

Cada registro en `data.raw_data` avanza por un ciclo de vida definido. Ninguna transicion ocurre fuera de los componentes documentados aqui.

## Diagrama

```
                    +----------+
                    |  PENDING |  <--- Consumer Kafka / recoverStuckProcessing()
                    +----------+
                         |
                         | Scheduler (claimPendingRecords)
                         v
                    +------------+
                    | PROCESSING |  (estado transitorio)
                    +------------+
                         |
              +----------+-----------+
              |                      |
           OK |                  error|
              v                      v
         +----------+          +-------+
         | GENERATED |         | ERROR |  retry_count++
         +----------+          +-------+
              |                      |
              | Scheduler            | retry_count >= 3
              | (publicacion Kafka)  v
              v                 +--------+
         +-----------+          | FAILED |
         | PUBLISHED |          +--------+
         +-----------+
```

## Estados

| Estado | Descripcion | Quien lo asigna |
|--------|-------------|----------------|
| `PENDING` | Solicitud recibida y disponible para procesamiento | `DocumentGenerationRequestedConsumer` (INSERT inicial) / `recoverStuckProcessing()` (reset de PROCESSING colgados) |
| `PROCESSING` | Scheduler procesando este registro | `DocumentGenerationScheduler` (al reclamar el registro) |
| `GENERATED` | PDF generado y `document_url` disponible en GCS | `DocumentGenerationScheduler` (exito del pipeline) |
| `PUBLISHED` | Evento publicado a Kafka. Ciclo completo. | `DocumentGenerationScheduler` (publicacion Kafka) |
| `ERROR` | Fallo en el scheduler, con reintentos disponibles | `DocumentGenerationScheduler` (fallo con `retry_count < 3`) |
| `FAILED` | Supero el limite de reintentos. Requiere intervencion manual. | `DocumentGenerationScheduler` (fallo con `retry_count >= 3`) |

## Reglas de transicion

- `PENDING -> PROCESSING`: al reclamar el registro en `claimPendingRecords()`. Si el pod muere aqui, `recoverStuckProcessing()` lo revierte a `PENDING`.
- `PROCESSING -> GENERATED`: solo cuando PDF fue generado Y subido exitosamente a GCS.
- `PROCESSING -> ERROR`: ante cualquier excepcion en el scheduler (template no encontrado, fallo GCS, JSON invalido, etc.).
- `ERROR -> PENDING`: leido en la siguiente ejecucion del scheduler si `retry_count < 3`.
- `ERROR -> FAILED`: cuando `retry_count >= 3`, el scheduler transiciona directamente a FAILED en lugar de ERROR.
- `GENERATED -> PUBLISHED`: solo cuando el evento fue publicado a Kafka Y el UPDATE fue commiteado.
- `FAILED`: estado terminal. `claimPendingRecords()` nunca lee registros FAILED. Solo se puede salir de FAILED via intervencion manual (reset a PENDING con `retry_count = 0`).
- `PUBLISHED`: estado terminal. No hay transicion de salida.

## Consultas utiles

```sql
-- Ver distribucion de estados
SELECT status, COUNT(*) FROM data.raw_data GROUP BY status ORDER BY 2 DESC;

-- Registros ERROR con reintentos disponibles (proxima ejecucion los procesara)
SELECT process_id, idempotency_id, retry_count, error_message
FROM data.raw_data
WHERE status = 'ERROR' AND retry_count < 3
ORDER BY updated_at;

-- Registros FAILED que requieren intervencion manual
SELECT process_id, idempotency_id, template_name, retry_count, error_message
FROM data.raw_data
WHERE status = 'FAILED'
ORDER BY updated_at DESC;

-- Latencia promedio PENDING -> GENERATED
SELECT AVG(EXTRACT(EPOCH FROM (processed_at - created_at))) AS avg_seconds
FROM data.raw_data
WHERE status IN ('GENERATED', 'PUBLISHED') AND processed_at IS NOT NULL;

-- Resetear un registro FAILED a PENDING (intervencion manual)
UPDATE data.raw_data
SET status = 'PENDING', retry_count = 0, error_message = NULL, updated_at = now()
WHERE process_id = <id>;
```

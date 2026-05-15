# Maquina de estados — raw_data.status

Cada registro en `batch.raw_data` avanza por un ciclo de vida definido. Ninguna transicion ocurre fuera de los componentes documentados aqui.

## Diagrama

```
                    +----------+
                    |  PENDING |  <--- Consumer Kafka / RecoveryListener
                    +----------+
                         |
                         | Step 1 - Processor (claim)
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
              | Step 2 - Writer      | retry_count >= 3
              v                      v
         +-----------+         +--------+
         | PUBLISHED |         | FAILED |
         +-----------+         +--------+
```

## Estados

| Estado | Descripcion | Quien lo asigna |
|--------|-------------|----------------|
| `PENDING` | Solicitud recibida y disponible para procesamiento | `DocumentGenerationRequestedConsumer` (INSERT inicial) / `RecoveryJobListener` (reset de PROCESSING colgados) |
| `PROCESSING` | Step 1 actualmente procesando este registro | `DocumentGenerationProcessor` (al inicio de cada item) |
| `GENERATED` | PDF generado y `document_url` disponible en GCS | `DocumentGenerationWriter` (exito del Processor) |
| `PUBLISHED` | Evento publicado a Kafka. Ciclo completo. | `DocumentPublicationWriter` (Step 2) |
| `ERROR` | Fallo en Step 1, con reintentos disponibles | `DocumentGenerationWriter` (fallo del Processor, `retry_count < 3`) |
| `FAILED` | Supero el limite de reintentos. Requiere intervencion manual. | `DocumentGenerationWriter` (fallo del Processor, `retry_count >= 3`) |

## Reglas de transicion

- `PENDING -> PROCESSING`: siempre al inicio del Processor. Si el pod muere aqui, el `RecoveryJobListener` lo revierte a `PENDING`.
- `PROCESSING -> GENERATED`: solo cuando PDF fue generado Y subido exitosamente a GCS.
- `PROCESSING -> ERROR`: ante cualquier excepcion en el Processor (template no encontrado, fallo GCS, JSON invalido, etc.).
- `ERROR -> PENDING`: lectura en la siguiente ejecucion del Job si `retry_count < 3`.
- `ERROR -> FAILED`: cuando `retry_count >= 3`, el Writer transiciona directamente a FAILED en lugar de ERROR.
- `GENERATED -> PUBLISHED`: solo cuando el evento fue publicado a Kafka Y el UPDATE fue commiteado (misma transaccion).
- `FAILED`: estado terminal. El Reader nunca lee registros FAILED. Solo se puede salir de FAILED via intervencion manual (reset a PENDING con `retry_count = 0`).
- `PUBLISHED`: estado terminal. No hay transicion de salida.

## Consultas utiles

```sql
-- Ver distribucion de estados
SELECT status, COUNT(*) FROM batch.raw_data GROUP BY status ORDER BY 2 DESC;

-- Registros ERROR con reintentos disponibles (proxima ejecucion los procesara)
SELECT process_id, idempotency_id, retry_count, error_message
FROM batch.raw_data
WHERE status = 'ERROR' AND retry_count < 3
ORDER BY updated_at;

-- Registros FAILED que requieren intervencion manual
SELECT process_id, idempotency_id, template_name, retry_count, error_message
FROM batch.raw_data
WHERE status = 'FAILED'
ORDER BY updated_at DESC;

-- Latencia promedio PENDING -> GENERATED
SELECT AVG(EXTRACT(EPOCH FROM (processed_at - created_at))) AS avg_seconds
FROM batch.raw_data
WHERE status IN ('GENERATED', 'PUBLISHED') AND processed_at IS NOT NULL;

-- Resetear un registro FAILED a PENDING (intervencion manual)
UPDATE batch.raw_data
SET status = 'PENDING', retry_count = 0, error_message = NULL, updated_at = now()
WHERE process_id = <id>;
```

# Recovery — Registros PROCESSING colgados

## Problema que resuelve

Cuando el scheduler reclama un registro, lo marca como `PROCESSING` en la base de datos. Si el pod se reinicia (OOM, deployment, crash) mientras el ciclo esta en ejecucion, esos registros quedan con `status = PROCESSING` indefinidamente. `claimPendingRecords()` no los recoge (solo lee `PENDING` y `ERROR`), por lo que quedarian bloqueados sin intervencion manual.

## Solucion — recoverStuckProcessing()

`recoverStuckProcessing()` es un metodo en `DocumentGenerationScheduler` que se ejecuta al inicio de cada ciclo, antes de reclamar nuevos registros.

### Logica

```
recoverStuckProcessing():
    threshold = now() - recoveryTimeoutMinutes   (default: 30 min)
    stuckRecords = findStuckProcessing(threshold)
    if stuckRecords.isEmpty() -> log "no stuck records" y retornar
    bulk UPDATE status = 'PENDING', updated_at = now() WHERE process_id IN (...)
    log cantidad de registros recuperados
```

### Parametros

| Parametro | Default | Config key |
|-----------|---------|------------|
| Timeout de recovery | 30 minutos | `document-generation.schedule.recovery-timeout-minutes` |

Un registro se considera "colgado" si:
- `status = PROCESSING`
- `updated_at < now() - timeout`

### Query de deteccion

```java
// RawDataRepository.findStuckProcessing(threshold)
SELECT r FROM RawData r
WHERE r.status = PROCESSING
  AND r.updatedAt < :threshold
```

---

## Configuracion

```yaml
document-generation:
  schedule:
    recovery-timeout-minutes: 30   # aumentar si el ciclo demora mucho
```

## Consideraciones de timeout

El timeout debe ser mayor que el tiempo maximo de procesamiento de un ciclo completo del scheduler. Con:
- `batch-size = 500`
- Tiempo de generacion PDF + GCS upload ~2s por item (single-threaded)

Un ciclo puede tardar hasta `500 * 2 = ~1000 segundos` en el peor caso. Un timeout de 30 minutos es conservador para lotes de 100 registros (~200s).

Si se reduce el timeout agresivamente y el servicio tarda mas que el timeout en procesar el lote, `recoverStuckProcessing()` puede resetear registros que aun estan siendo procesados en otra replica, causando duplicados. Ajustar `batch-size` y timeout de forma coherente.

---

## Escenarios cubiertos

| Escenario | Comportamiento |
|-----------|----------------|
| Pod crashea durante el ciclo | Los registros quedan PROCESSING; en la siguiente ejecucion del scheduler, `recoverStuckProcessing()` los resetea a PENDING |
| Deployment rolling update | Misma cobertura que crash; el nuevo pod recupera los registros |
| Timeout de base de datos durante el ciclo | Idem |
| Ciclo normal (sin crash) | `recoverStuckProcessing()` no encuentra registros; continua normal |
| Multiples replicas del servicio | Cada replica tiene su propio scheduler; `recoverStuckProcessing()` de cada una resetea solo los registros vencidos, no los activos de otras replicas (por el threshold de tiempo) |

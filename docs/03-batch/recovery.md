# Recovery — Registros PROCESSING colgados

## Problema que resuelve

Cuando el Step 1 procesa un item, el Processor marca su estado como `PROCESSING` en la base de datos. Si el pod se reinicia (OOM, deployment, crash) mientras un chunk esta en ejecucion, esos registros quedan con `status = PROCESSING` indefinidamente. El Reader normal no los recoge (solo lee `PENDING` y `ERROR`), por lo que quedarian bloqueados sin intervencion manual.

## Solucion — RecoveryJobListener

`RecoveryJobListener` implementa `JobExecutionListener` y actua en `beforeJob()`, antes de que el Job inicie cualquier Step.

### Logica

```
beforeJob():
    threshold = now() - recoveryTimeoutMinutes   (default: 30 min)
    stuckRecords = findStuckProcessing(threshold)
    if stuckRecords.isEmpty() -> log "no stuck records" y retornar
    bulk UPDATE status = 'PENDING', updated_at = now() WHERE process_id IN (...)
    log cantidad de registros recuperados
```

### Parametros

| Parametro | Default | Config key |
|-----------|---------|------------|
| Timeout de recovery | 30 minutos | `document-generation.batch.recovery-timeout-minutes` |

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
  batch:
    recovery-timeout-minutes: 30   # aumentar si los chunks son muy grandes
```

## Consideraciones de timeout

El timeout debe ser mayor que el tiempo maximo de procesamiento de un chunk completo. Con:
- `chunk-size = 500`
- `thread-pool.max-size = 32`
- Tiempo de generacion PDF + GCS upload ~2s por item

Un chunk puede tardar hasta `500 / 32 * 2 = ~31 segundos`. Un timeout de 30 minutos es conservador y seguro.

Si se reduce el timeout agresivamente (ej. 5 min) y el servicio tarda mas de 5 min en procesar un chunk, el RecoveryListener reseteara registros que aun estan siendo procesados, causando duplicados.

---

## Escenarios cubiertos

| Escenario | Comportamiento |
|-----------|----------------|
| Pod crashea mid-chunk | Los registros quedan PROCESSING; en la siguiente ejecucion del Job, RecoveryListener los resetea a PENDING |
| Deployment rolling update | Misma cobertura que crash; el nuevo pod recupera los registros |
| Timeout de base de datos mid-chunk | Idem |
| Chunk normal (sin crash) | RecoveryListener no encuentra registros; continua normal |
| Multiples replicas del servicio | Cada replica tiene su propio Job; RecoveryListener de cada una resetea solo los registros vencidos, no los activos de otras replicas |

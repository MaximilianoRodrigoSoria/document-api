# Tuning de rendimiento del Scheduler

## Parámetros configurables

| Parámetro | Default local | Default prod | Descripción |
|-----------|--------------|--------------|-------------|
| `schedule.cron` | `*/30 * * * * *` | `0 */5 * * * *` | Frecuencia del cron |
| `schedule.batch-size` | `20` | `100` | Registros reclamados por ciclo |
| `schedule.recovery-timeout-minutes` | `5` | `30` | Minutos de inactividad para considerar un registro "colgado" |
| HikariCP `maximum-pool-size` | `10` | `80` | Conexiones DB máximas |

### Configuración YAML

```yaml
document-generation:
  schedule:
    cron: "0 */5 * * * *"
    batch-size: 100
    recovery-timeout-minutes: 30

spring:
  datasource:
    hikari:
      maximum-pool-size: 80
      minimum-idle: 25
      connection-timeout: 30000
```

---

## Cálculo de capacidad estimada

El scheduler procesa registros de forma **secuencial** dentro de cada ciclo. La capacidad depende del tiempo de procesamiento por registro:

| Variable | Valor estimado |
|----------|---------------|
| Tiempo por registro (generación PDF + upload GCS) | ~500ms–2s |
| `batch-size` | 100 |
| Tiempo por ciclo (100 registros) | ~50s–200s |
| Cron cada 5 minutos | ~3 ciclos por lote de 100 → ~300 registros / 5 min |

Para cargas superiores a ~300 registros / 5 minutos, considerar:
1. Aumentar `batch-size` (procesa más en un ciclo)
2. Reducir el intervalo del cron (más ciclos por unidad de tiempo)
3. Desplegar múltiples réplicas (cada una corre su propio scheduler — seguro por `FOR UPDATE SKIP LOCKED`)

---

## Hot path — optimización por TemplateCache

El `TemplateCache` singleton elimina operaciones repetidas cuando múltiples registros comparten el mismo template:

| Operación | Sin caché | Con caché |
|-----------|-----------|-----------|
| Query BD `findByNameAndStatus()` | × N registros | × T templates únicos |
| Descarga GCS (bytes PDF) | × N registros | × T templates únicos |
| `HtmlPdfDocumentGenerator.generate()` | × N registros | × N registros (inevitable) |
| GCS upload documento | × N registros | × N registros (inevitable) |

Con N = 100 registros y T = 2 templates: las primeras dos filas pasan de 100 operaciones a 2.

La caché persiste entre ciclos del cron. Al subir una nueva versión de template via API, `invalidate(templateName)` fuerza recarga en el próximo ciclo.

---

## Ajuste por tipo de carga

### Pocos templates (alta repetición)
El `TemplateCache` es máximamente eficiente. Aumentar `batch-size` para procesar más registros por ciclo.

### Muchos templates distintos
El beneficio del caché es proporcional a la repetición. Si cada registro usa un template diferente, no hay ganancia de caché; el cuello de botella es GCS.

### PDFs grandes (muchos campos, páginas)
Reducir `batch-size` para evitar picos de memoria. Flying Saucer carga el documento HTML completo en memoria durante la conversión a PDF.

### Multi-réplica
`FOR UPDATE SKIP LOCKED` garantiza que dos réplicas nunca tomen el mismo registro. Cada réplica tiene su propio `TemplateCache` singleton. La suma de `batch-size × réplicas` determina la capacidad total por ciclo.

---

## Regla HikariCP

Con el scheduler single-threaded por diseño, las transacciones son secuenciales. El pool de conexiones no necesita ser grande. La fórmula mínima para producción:

```
maximum-pool-size >= réplicas + 5 (consumer Kafka + conexiones idle)
```

Con 1 réplica: `maximum-pool-size = 10` es suficiente. El default de producción (`80`) es conservador y permite crecimiento futuro.

# Jobs API

> **Deprecado.** Esta API existía cuando el servicio usaba Spring Batch con un endpoint de trigger manual (`POST /v1/jobs/document-generation/run`).
>
> Tras la migración al scheduler cron (`@Scheduled`), el Job es disparado automáticamente por el cron configurado en `document-generation.schedule.cron`. No existe endpoint de trigger manual en esta versión.

## Acciones disponibles actualmente

**Forzar procesamiento inmediato (sin esperar el cron):**

Temporalmente, la forma más directa es ajustar el cron a una frecuencia alta en `application-local.yml`:

```yaml
document-generation:
  schedule:
    cron: "*/10 * * * * *"   # cada 10 segundos
```

O reiniciar el servicio: el scheduler dispara su primer ciclo al arrancar.

**Ver estado de los registros:**

```sql
SELECT status, count(*) FROM data.raw_data GROUP BY status;
```

**Reactivar registros FAILED manualmente:**

```sql
UPDATE data.raw_data
SET status = 'PENDING', retry_count = 0, error_message = NULL, updated_at = now()
WHERE status = 'FAILED' AND template_name = '<nombre>';
```

Ver [runbook.md](../07-operations/runbook.md) para más escenarios operacionales.

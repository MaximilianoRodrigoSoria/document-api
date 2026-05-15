# ADR-001 — Generacion Asincrona de Documentos

## Informacion General

| Campo | Valor |
|-------|-------|
| ID | ADR-001 |
| Titulo | Generacion Asincrona de Documentos |
| Estado | **Aceptado** |
| Fecha | 2026-05-05 |
| Dominio | Generacion documental |
| Sistemas involucrados | document-generator-api, Kafka (KRaft), PostgreSQL 15.2, Google Cloud Storage |

> **Nota:** El almacenamiento fue migrado de AWS S3 a Google Cloud Storage. Ver [ADR-002](ADR-002-gcs-sobre-s3.md).

---

## Indice

1. [Contexto](#contexto)
2. [Problema](#problema)
3. [Decision](#decision)
4. [Flujo completo](#flujo-completo)
5. [Componentes](#componentes)
6. [Modelo de Evento](#modelo-de-evento)
7. [Maquina de estados](#maquina-de-estados)
8. [Decisiones tecnicas clave](#decisiones-tecnicas-clave)
9. [Alternativas Evaluadas](#alternativas-evaluadas)
10. [Consecuencias](#consecuencias)
11. [Riesgos](#riesgos)
12. [Seguridad](#seguridad)
13. [Plan de Implementacion](#plan-de-implementacion)
14. [Criterios de Aceptacion](#criterios-de-aceptacion)
15. [Decision Final](#decision-final)

---

## Contexto

Un servicio productor externo requiere generar documentos asociados a sus entidades de negocio (el dominio inicial es **MISIONES**, con posibilidad de expandir a otros dominios como `loans`, `cards`).

La generacion de documentos involucra procesamiento costoso: renderizado de templates HTML Mustache a PDF con Flying Saucer, almacenamiento en GCS. Este proceso no debe ocurrir de forma sincronica dentro del flujo transaccional del servicio productor.

Se propone desacoplar la generacion de documentos mediante Kafka y un servicio especializado: **document-generator-api**.

---

## Problema

Cuando el servicio productor necesita generar un documento, el problema es evitar que asuma responsabilidades fuera de su dominio:

- Generacion fisica de documentos (renderizado HTML, conversion PDF)
- Manejo de templates y cache
- Persistencia del estado de generacion
- Almacenamiento de archivos en buckets
- Reintentos, control de errores y trazabilidad del pipeline

---

## Decision

Se implementa una arquitectura asincrona basada en eventos, donde el servicio productor externo publica una solicitud de generacion en Kafka y `document-generator-api` se encarga de todo el pipeline posterior.

---

## Flujo completo

```
Servicio productor externo
    |
    v Kafka [document.generation.requested]
    |
    v DocumentGenerationRequestedConsumer
    |   - Verifica idempotency_id (UNIQUE constraint)
    |   - INSERT data.raw_data (status = PENDING, retry_count = 0)
    |
    v @Scheduled(fixedDelay) → DocumentGenerationScheduler
    |
    +-- TX1: recoverStuckProcessing()
    |   - Resetea registros PROCESSING colgados -> PENDING (timeout configurable)
    |
    +-- TX2: claimPendingRecords(batchSize)
    |   - SELECT WHERE status IN (PENDING, ERROR) AND retry_count < 3
    |     FOR UPDATE SKIP LOCKED → UPDATE status = PROCESSING
    |
    +-- Por cada registro (secuencial dentro del ciclo):
    |   - templateCache.getCompiledTemplate(templateName)
    |   - deserializar data (JSONB) → Map<String, Object>
    |   - HtmlPdfDocumentGenerator.generate(compiledTemplate, fields)
    |       - Mustache.execute(fields) → HTML string
    |       - Flying Saucer ITextRenderer → PDF bytes
    |   - gcsStorageClient.uploadDocument(idempotencyId, domain, pdfBytes)
    |   - TX3: markGenerated(record, documentUrl) → status = GENERATED
    |   - publisher.publish(topic, event)           → Kafka
    |   - TX4: markPublished(record)                → status = PUBLISHED
    |   - si error: TX5: markError(record, message) → status = ERROR/FAILED
    |
    v Kafka [document.generation.completed]
    |
    v Servicio productor externo
        - Recibe idempotency_id + document_url
        - Actualiza su dominio con el link del documento generado
```

---

## Componentes

### Servicio productor externo

**Responsabilidades:**
- Publicar la solicitud de generacion de documento a Kafka.
- Escuchar el evento de documento generado y actualizar su entidad con el `document_url`.

**No debe:** generar documentos, gestionar templates, operar GCS ni ejecutar procesamiento batch.

### document-generator-api

**Responsabilidades:**
- Consumir solicitudes de generacion desde Kafka.
- Persistir solicitudes con estado inicial `PENDING`.
- Procesar solicitudes en ciclos del scheduler (`@Scheduled fixedDelay`).
- Generar documentos PDF a partir de templates HTML Mustache + Flying Saucer.
- Guardar documentos en Google Cloud Storage.
- Controlar el ciclo de vida completo (PENDING → PUBLISHED).
- Publicar eventos de resultado.
- Mantener el catalogo de templates (`data.document_templates`).

### Kafka (KRaft)

**Topics:**
- `document.generation.requested` — entrada
- `document.generation.completed` — salida

### PostgreSQL 15.2

**Schema:** `data`. **Base de datos:** `document_generator_db`.

Tablas principales: `data.raw_data` y `data.document_templates`.

### Google Cloud Storage

Almacena los documentos PDF generados bajo la estructura `gs://<bucket>/<domain>/<idempotencyId>.pdf`.
El acceso al bucket debe ser privado; se evalua el uso de URLs firmadas para exposicion controlada.

---

## Modelo de Evento

### Evento de solicitud

Topic: `document.generation.requested` | Clave de particion: `idempotencyId`

```json
{
  "idempotencyId": "550e8400-e29b-41d4-a716-446655440000",
  "domain":        "misiones",
  "templateName":  "mission-template-v1",
  "fields": {
    "missionName": "Compra en comercios adheridos",
    "startDate":   "2026-05-05",
    "endDate":     "2026-06-05",
    "conditions":  "Aplica para clientes segmentados"
  }
}
```

### Evento de resultado

Topic: `document.generation.completed` | Clave de particion: `idempotencyId`

```json
{
  "idempotencyId": "550e8400-e29b-41d4-a716-446655440000",
  "domain":        "misiones",
  "templateName":  "mission-template-v1",
  "documentUrl":   "gs://document-generator-bucket/misiones/550e8400-e29b-41d4-a716-446655440000.pdf",
  "status":        "GENERATED",
  "processedAt":   "2026-05-06T15:00:00"
}
```

---

## Maquina de estados

```
PENDING
   |
   v Scheduler (claimPendingRecords)
PROCESSING ---- error ---> ERROR ---- retry_count >= 3 ---> FAILED
   |
   v OK
GENERATED
   |
   v Scheduler (publicacion Kafka)
PUBLISHED
```

| Estado | Descripcion |
|--------|-------------|
| `PENDING` | Solicitud recibida y persistida por el consumer Kafka |
| `PROCESSING` | Scheduler procesando (estado transitorio) |
| `GENERATED` | PDF generado y `document_url` disponible en GCS |
| `PUBLISHED` | Evento publicado a Kafka. Ciclo de vida completo. |
| `ERROR` | Fallo en el scheduler. Se reintentara si `retry_count < 3`. |
| `FAILED` | Supero 3 intentos. Requiere intervencion manual. |

---

## Decisiones tecnicas clave

### Idempotencia en doble sentido

El `idempotency_id` tiene constraint `UNIQUE` en `data.raw_data`. Si el ID ya existe y no es `FAILED` → ACK sin persistir. Si es `FAILED` → re-insert como `PENDING`. El consumidor de `document.generation.completed` debe manejar duplicados por `idempotencyId`.

### Concurrencia con FOR UPDATE SKIP LOCKED

Permite ejecutar multiples replicas del servicio sin que dos instancias procesen el mismo registro. Cada replica bloquea su lote y las demas lo saltan.

### TemplateCache singleton

Mantiene dos `ConcurrentHashMap` entre ciclos del scheduler: `entityCache` (metadata BD) y `compiledCache` (template Mustache compilado). El primer acceso por `templateName` consulta BD y compila el Mustache; los accesos posteriores son hits de memoria O(1). Con 10k registros de 3 templates → 3 queries en lugar de 10k.

### Recovery de estados colgados

`recoverStuckProcessing()` se llama al inicio de cada ciclo del scheduler. Detecta registros con `status = PROCESSING` cuyo `updated_at` supera el timeout configurado y los resetea a `PENDING`. Cubre crashes mid-processing sin intervencion manual.

### Retry con limite de 3 intentos

`markError()` incrementa `retry_count` en cada fallo. Cuando `retry_count >= 3`, el status pasa a `FAILED` permanente. Evita bucles infinitos ante errores estructurales.

### fixedDelay en lugar de cron

El countdown comienza cuando el ciclo termina, evitando solapamientos si el procesamiento de un lote dura mas que el intervalo configurado.

---

## Alternativas Evaluadas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Generacion sincronica en el servicio productor | Menos componentes | Acoplamiento alto, latencia, riesgo de timeout |
| **Generacion asincrona Kafka + servicio dedicado (SELECCIONADA)** | Desacople, escalabilidad, resiliencia | Mayor complejidad operativa |
| Job interno dentro del servicio productor | Menos despliegues | Mezcla responsabilidades |

---

## Consecuencias

### Positivas

- Desacople de la logica del servicio productor de la generacion documental.
- No bloquea el flujo transaccional del servicio productor.
- Escalabilidad mediante scheduler con batch-size configurable y multiples replicas.
- Reintentos, estados y errores controlados y trazables.
- Reutilizable para multiples dominios.

### Negativas

- Nuevo componente a operar, monitorear y mantener.
- Consistencia eventual (el `document_url` no esta disponible inmediatamente).
- Los consumidores de `document.generation.completed` deben ser idempotentes.
- Politicas claras para registros `FAILED` (alertas, reprocesamiento manual).

---

## Riesgos

| Riesgo | Impacto | Mitigacion |
|--------|---------|------------|
| Evento duplicado en Kafka | Doble procesamiento | `idempotency_id` UNIQUE en DB |
| Fallo al guardar en GCS | PDF no disponible | `retry_count`, estado ERROR/FAILED, alertas |
| Fallo al publicar evento completado | Servicio productor no recibe la URL | Estado GENERATED separado de PUBLISHED; scheduler reintenta en proximo ciclo |
| Template inexistente o corrupto | Error de generacion | Validacion previa con catalogo; error capturado en el scheduler |
| Alto volumen de documentos | Saturacion del scheduler | `batch-size` configurable, multiples replicas con SKIP LOCKED |
| Crash mid-processing | Registros colgados en PROCESSING | `recoverStuckProcessing()` los detecta y resetea a PENDING |

---

## Seguridad

- El `document_url` no debe exponer informacion sensible sin control. Evaluar URLs firmadas de GCS.
- El bucket debe tener acceso privado por defecto.
- Los eventos Kafka no deben transportar datos sensibles innecesarios.
- Los campos del documento (`fields`) deben validarse antes de procesarse.
- Se deben auditar generacion y descarga de documentos (logs estructurados con MDC).

---

## Criterios de Aceptacion

- El servicio productor publica correctamente la solicitud de generacion al topic Kafka.
- El consumer persiste la solicitud como `PENDING` con idempotencia correcta.
- El scheduler genera el PDF, lo sube a GCS y actualiza el estado a `GENERATED`.
- El scheduler publica el evento Kafka y actualiza el estado a `PUBLISHED`.
- Los errores se reintentan hasta 3 veces y quedan como `FAILED` con mensaje descriptivo.
- Los registros `PROCESSING` colgados son detectados y recuperados por `recoverStuckProcessing()`.
- El servicio productor actualiza su entidad con el `documentUrl` al recibir el evento completado.
- El flujo soporta reprocesamiento sin duplicar documentos (idempotencia).

---

## Decision Final

Se adopta la arquitectura de scheduler basada en `@Scheduled(fixedDelay)` con `DocumentGenerationScheduler` como orquestador del ciclo completo. Esta decision reemplaza cualquier implementacion previa basada en Spring Batch (Steps, Readers, Processors, Writers). Ver historial de cambios en el repositorio Git.

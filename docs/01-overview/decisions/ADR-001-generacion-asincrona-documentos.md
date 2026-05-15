# ADR-001 — Generacion Asincrona de Documentos para Misiones Segmentadas

## Informacion General

| Campo | Valor |
|-------|-------|
| ID | ADR-001 |
| Titulo | Generacion Asincrona de Documentos para Misiones Segmentadas |
| Estado | **Aceptado** |
| Fecha | 2026-05-05 |
| Dominio | Loyalty / Misiones |
| Sistemas involucrados | payment-loyalty, batch-document-generator, Kafka (KRaft), PostgreSQL 15.2, Google Cloud Storage |
| Autor | Maximiliano Soria |

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
16. [Equipo](#equipo)

---

## Contexto

El sistema `payment-loyalty` permite la creacion de misiones. Algunas misiones requieren generar documentos asociados basados en templates del dominio **MISIONES** (y potencialmente otros dominios en el futuro, como `loans`, `cards`).

La generacion de documentos involucra procesamiento pesado: llenado de campos dinamicos desde templates PDF, exportacion y almacenamiento en GCS. Este proceso no debe ocurrir de forma sincronica dentro del flujo transaccional de creacion de una mision.

Se propone desacoplar la generacion de documentos mediante Kafka y un componente batch especializado: **batch-document-generator**.

---

## Problema

Cuando se crea una mision, el sistema necesita generar un documento sin bloquear el flujo principal de negocio.

El problema es evitar que `payment-loyalty` asuma responsabilidades fuera de su dominio:

- Generacion fisica de documentos (PDFBox, JasperReports, etc.)
- Manejo de templates y cache de archivos
- Procesamiento por lotes de alto volumen
- Persistencia del estado de generacion
- Almacenamiento de archivos en buckets
- Reintentos, control de errores y trazabilidad del pipeline

---

## Decision

Se implementa una arquitectura asincrona basada en eventos, donde `payment-loyalty` publica una solicitud de generacion en Kafka y `batch-document-generator` se encarga de todo el pipeline posterior.

---

## Flujo completo

```
payment-loyalty
    |
    v Kafka [document.generation.requested]
    |
    v DocumentGenerationRequestedConsumer
    |   - Verifica idempotency_id (UNIQUE constraint)
    |   - INSERT batch.raw_data (status = PENDING, retry_count = 0)
    |
    v @Scheduled / REST endpoint -> DocumentGenerationJob
    |
    +-- RecoveryJobListener.beforeJob()
    |   - Resetea registros PROCESSING colgados -> PENDING (timeout: 30 min)
    |
    +-- Step 1: generationStep
    |   - Reader:    SELECT WHERE status IN (PENDING, ERROR) AND retry_count < 3
    |                ORDER BY template_name, created_at
    |                FOR UPDATE SKIP LOCKED
    |   - Cache:     TemplateCache @StepScope -- descarga template de GCS una vez por templateName
    |   - Processor: Marca PROCESSING -> Genera PDF -> Sube a GCS -> Retorna item con document_url
    |   - Writer:    UPDATE status = GENERATED (exito)
    |                UPDATE status = ERROR, retry_count++ (fallo recuperable)
    |                UPDATE status = FAILED (retry_count >= 3, permanente)
    |
    +-- Step 2: publicationStep
        - Reader:  SELECT WHERE status = GENERATED LIMIT 100
        - Writer:  Publica Kafka [document.generation.completed]
                   UPDATE status = PUBLISHED, published_at
                   (dentro de la misma transaccion Spring Batch)
    |
    v Kafka [document.generation.completed]
    |
    v payment-loyalty
        - Recibe idempotency_id + document_url
        - Actualiza su dominio con el link del documento generado
```

---

## Componentes

### payment-loyalty

**Responsabilidades:**
- Crear misiones y publicar la solicitud de generacion de documento.
- Escuchar el evento de documento generado y actualizar la entidad con el `document_url`.

**No debe:** generar documentos, gestionar templates, operar GCS ni ejecutar procesamiento batch.

### batch-document-generator

**Responsabilidades:**
- Consumir solicitudes de generacion desde Kafka.
- Persistir solicitudes con estado inicial `PENDING`.
- Procesar solicitudes por lote (Spring Batch, chunk size configurable).
- Generar documentos PDF a partir de templates.
- Guardar documentos en Google Cloud Storage.
- Controlar el ciclo de vida completo (PENDING -> PUBLISHED).
- Publicar eventos de resultado.
- Mantener el catalogo de templates (`batch.document_templates`).

### Kafka (KRaft)

**Topics:**
- `document.generation.requested` -- entrada
- `document.generation.completed` -- salida

### PostgreSQL 15.2

**Schema:** `batch`. **Base de datos:** `document_generator_db`.

Tablas principales: `batch.raw_data` y `batch.document_templates`. Las tablas de metadata de Spring Batch tambien viven en el schema `batch`.

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
  "documentUrl":   "gs://batch-document-generator/misiones/550e8400-e29b-41d4-a716-446655440000.pdf",
  "status":        "GENERATED",
  "processedAt":   "2026-05-06T15:00:00"
}
```

---

## Maquina de estados

```
PENDING
   |
   v Step 1 (Processor)
PROCESSING ---- error ---> ERROR ---- retry_count >= 3 ---> FAILED
   |
   v OK (Writer)
GENERATED
   |
   v Step 2 (Writer)
PUBLISHED
```

| Estado | Descripcion |
|--------|-------------|
| `PENDING` | Solicitud recibida y persistida por el consumer Kafka |
| `PROCESSING` | Step 1 en ejecucion (estado transitorio) |
| `GENERATED` | PDF generado y `document_url` disponible en GCS |
| `PUBLISHED` | Evento publicado a Kafka. Ciclo de vida completo. |
| `ERROR` | Fallo en el Processor. Se reintentara si `retry_count < 3`. |
| `FAILED` | Supero 3 intentos. Requiere intervencion manual. |

---

## Decisiones tecnicas clave

### Idempotencia en doble sentido

El `idempotency_id` tiene constraint `UNIQUE` en `batch.raw_data`. Si el ID ya existe y no es `FAILED` -> ACK sin persistir. Si es `FAILED` -> re-insert como `PENDING`. El consumidor de `document.generation.completed` debe manejar duplicados por `idempotencyId`.

### Concurrencia con FOR UPDATE SKIP LOCKED

Permite ejecutar multiples replicas del servicio (Kubernetes HPA) sin que dos workers procesen el mismo registro. Cada replica bloquea su lote y los demas lo saltan.

### TemplateCache @StepScope

Mantiene un `Map<String, byte[]>` durante el Step 1. El Reader ordena por `template_name`, maximizando el hit rate. Con 10k registros de 3 templates -> 3 descargas GCS en lugar de 10k.

### Recovery de estados colgados

`RecoveryJobListener.beforeJob()` detecta registros con `status = PROCESSING` cuyo `updated_at` supera el timeout (default: 30 min) y los resetea a `PENDING`. Cubre crashes mid-chunk sin intervencion manual.

### Retry con limite de 3 intentos

El Writer incrementa `retry_count` en cada fallo. Cuando `retry_count >= 3`, el status pasa a `FAILED` permanente. Evita bucles infinitos ante errores estructurales.

---

## Alternativas Evaluadas

| Alternativa | Ventajas | Desventajas |
|-------------|----------|-------------|
| Generacion sincronica en `payment-loyalty` | Menos componentes | Acoplamiento alto, latencia, riesgo de timeout |
| **Generacion asincrona Kafka + batch dedicado (SELECCIONADA)** | Desacople, escalabilidad, resiliencia | Mayor complejidad operativa |
| Job interno dentro de `payment-loyalty` | Menos despliegues | Mezcla responsabilidades |

---

## Consecuencias

### Positivas

- Desacople de la creacion de misiones de la generacion documental.
- No bloquea el flujo transaccional de `payment-loyalty`.
- Escalabilidad mediante procesamiento batch con chunks y multi-threading.
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
| Fallo al publicar evento completado | `payment-loyalty` no actualiza el link | Estado GENERATED separado de PUBLISHED; Step 2 reintenta en proxima ejecucion |
| Template inexistente o corrupto en GCS | Error de generacion | Validacion previa con catalogo; error capturado en Processor |
| Alto volumen de documentos | Saturacion del batch | Chunk size configurable, thread pool hasta 32 cores, escalado horizontal con SKIP LOCKED |
| Crash mid-chunk | Registros colgados en PROCESSING | `RecoveryJobListener` los detecta y resetea a PENDING |

---

## Seguridad

- El `document_url` no debe exponer informacion sensible sin control. Evaluar URLs firmadas de GCS.
- El bucket debe tener acceso privado por defecto.
- Los eventos Kafka no deben transportar datos sensibles innecesarios.
- Los campos del documento (`fields`) deben validarse antes de procesarse.
- Se deben auditar generacion y descarga de documentos (logs estructurados con MDC).

---

## Criterios de Aceptacion

- Al crear una mision, `payment-loyalty` publica correctamente la solicitud de generacion.
- El consumer persiste la solicitud como `PENDING` con idempotencia correcta.
- El Step 1 genera el PDF, lo sube a GCS y actualiza el estado a `GENERATED`.
- El Step 2 publica el evento y actualiza el estado a `PUBLISHED`.
- Los errores se reintentan hasta 3 veces y quedan como `FAILED` con mensaje descriptivo.
- Los registros `PROCESSING` colgados son detectados y recuperados por el `RecoveryJobListener`.
- `payment-loyalty` actualiza la mision con el `documentUrl` al recibir el evento completado.
- El flujo soporta reprocesamiento sin duplicar documentos (idempotencia).

---

## Equipo

| Nombre | Version | Cambio |
|--------|---------|--------|
| M. Soria | 0.0.1 | Inicio |
| M. Soria | 0.1.0 | Rediseno: 2 Steps, TemplateCache, RecoveryListener, retry_count |
| M. Soria | 0.2.0 | Migracion S3 -> GCS (ver ADR-002) |

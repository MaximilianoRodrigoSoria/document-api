# Base de datos — Esquema y migraciones

## Motor

PostgreSQL 15.2. Las migraciones son gestionadas por **Flyway** desde `src/main/resources/db/migration/`.

- Schema principal: `data`
- Base de datos: `files`
- Flyway gestiona el schema completo; no hay tablas de Spring Batch.

---

## Migraciones

| Versión | Archivo | Descripción |
|---------|---------|-------------|
| V1 | `V1__init.sql` | Crea el schema `data`, tabla `data.raw_data` y tabla `data.document_templates` con todos los índices |
| V2 | `V2__seed_templates.sql` | Inserta el template inicial `compra-y-gana-empresas-prueba-002` |

> **Nota para arranque limpio (local):** si el schema `data` ya tiene tablas sin historial de Flyway (por ejemplo, tras una migración fallida anterior), ejecutar:
> ```bash
> docker exec -it document-generator-api-postgres psql -U user -d files \
>   -c "DROP SCHEMA data CASCADE; CREATE SCHEMA data;"
> ```
> Luego arrancar la app y Flyway ejecuta V1 y V2 desde cero.

---

## Tabla `data.raw_data`

Almacena el ciclo de vida completo de cada solicitud de generación de documento.

```sql
CREATE TABLE data.raw_data (
    process_id      SERIAL        NOT NULL,
    type            VARCHAR(255),
    data            JSONB,
    status          VARCHAR(45)   NOT NULL DEFAULT 'PENDING',
    idempotency_id  VARCHAR(255),
    template_name   VARCHAR(255),
    retry_count     INT           NOT NULL DEFAULT 0,
    document_url    VARCHAR(1000),
    error_message   TEXT,
    processed_at    TIMESTAMP,
    published_at    TIMESTAMP,
    created_at      TIMESTAMP     NOT NULL DEFAULT now(),
    created_by      VARCHAR(255),
    updated_at      TIMESTAMP     NOT NULL DEFAULT now(),
    updated_by      VARCHAR(255),

    CONSTRAINT pk_raw_data PRIMARY KEY (process_id),
    CONSTRAINT uq_raw_data_idempotency UNIQUE (idempotency_id)
);
```

### Columnas

| Columna | Tipo | Descripción |
|---------|------|-------------|
| `process_id` | `SERIAL` | PK autogenerada secuencialmente |
| `idempotency_id` | `VARCHAR(255)` | Clave de idempotencia del evento Kafka. Constraint `UNIQUE`. Previene inserciones duplicadas. |
| `template_name` | `VARCHAR(255)` | Nombre del template a usar. Columna propia (no enterrada en JSONB) para ORDER BY e indexación eficiente. |
| `type` | `VARCHAR(255)` | Dominio/clasificación del documento (ej. `MISIONES`). Determina la carpeta GCS destino. |
| `data` | `JSONB` | Variables dinámicas del template serializadas como JSON. Las claves deben coincidir con los campos AcroForm del template. |
| `status` | `VARCHAR(45)` | Estado del ciclo de vida. Ver [estados-raw-data.md](../02-domain/estados-raw-data.md). |
| `retry_count` | `INT` | Contador de intentos fallidos. DEFAULT 0. Al superar el límite (default: 3) → `FAILED`. |
| `document_url` | `VARCHAR(1000)` | URI GCS del PDF generado (`gs://bucket/domain/idempotencyId.pdf`). Nulo hasta `GENERATED`. |
| `error_message` | `TEXT` | Mensaje del último error. Truncado a 1000 chars. |
| `processed_at` | `TIMESTAMP` | Fecha/hora en que el PDF fue generado y subido a GCS. |
| `published_at` | `TIMESTAMP` | Fecha/hora en que el evento fue publicado a Kafka. |
| `created_at` | `TIMESTAMP` | Fecha/hora de creación del registro. |
| `updated_at` | `TIMESTAMP` | Fecha/hora de última modificación. Usado por el scheduler para detectar registros colgados. |

### Máquina de estados

```
PENDING → PROCESSING → GENERATED → PUBLISHED
                  └─ ERROR (reintentable) → FAILED (terminal)
```

### Índices

| Índice | Columnas | Propósito |
|--------|----------|-----------|
| `pk_raw_data` | `process_id` | PK |
| `uq_raw_data_idempotency` | `idempotency_id` | Idempotencia del consumer Kafka |
| `idx_raw_data_status` | `status` | Filtro del scheduler |
| `idx_raw_data_template_name` | `template_name` | ORDER BY para optimizar TemplateCache |
| `idx_raw_data_status_retry` | `status, retry_count` | Query compuesta de `claimPendingRecords` |

---

## Tabla `data.document_templates`

Catálogo de templates de documentos PDF disponibles para generación.

```sql
CREATE TABLE data.document_templates (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    domain      VARCHAR(100) NOT NULL,
    gcs_key     VARCHAR(1000) NOT NULL,
    version     VARCHAR(50)  NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    description TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_document_templates PRIMARY KEY (id),
    CONSTRAINT uq_document_templates_name UNIQUE (name)
);
```

| Columna | Tipo | Descripción |
|---------|------|-------------|
| `id` | `UUID` | PK generada automáticamente |
| `name` | `VARCHAR(255)` | Nombre lógico del template. Coincide con `raw_data.template_name`. Único. |
| `domain` | `VARCHAR(100)` | Dominio funcional (ej. `MISIONES`) |
| `gcs_key` | `VARCHAR(1000)` | Path del PDF en el bucket GCS (ej. `templates/compra-y-gana-empresas-prueba-002.pdf`) |
| `version` | `VARCHAR(50)` | Versión semántica (ej. `1.0.0`) |
| `status` | `VARCHAR(20)` | Estado: `ACTIVE`, `INACTIVE`, `DEPRECATED` |
| `description` | `TEXT` | Descripción del propósito del template |

Solo los templates con `status = ACTIVE` son usados por el `TemplateCache`.

---

## Consultas útiles

```sql
-- Estado general de solicitudes
SELECT status, count(*) FROM data.raw_data GROUP BY status ORDER BY count DESC;

-- Registros en error con mensaje
SELECT process_id, idempotency_id, template_name, retry_count, error_message
FROM data.raw_data
WHERE status IN ('ERROR', 'FAILED')
ORDER BY updated_at DESC;

-- Registros PROCESSING colgados (más de 30 min)
SELECT process_id, idempotency_id, template_name, updated_at,
       EXTRACT(EPOCH FROM (now() - updated_at)) / 60 AS minutes_stuck
FROM data.raw_data
WHERE status = 'PROCESSING'
  AND updated_at < now() - INTERVAL '30 minutes';

-- Templates disponibles
SELECT name, domain, gcs_key, version, status FROM data.document_templates ORDER BY name;

-- Historial de migraciones Flyway
SELECT version, description, success, installed_on
FROM data.flyway_schema_history
ORDER BY installed_rank;
```

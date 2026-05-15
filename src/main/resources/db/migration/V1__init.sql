-- ─────────────────────────────────────────────────────────────────────────────
-- V1__init.sql
-- Esquema inicial completo del servicio document-generator-api.
-- Crea el schema "data" y las dos tablas principales en su estado final.
--
-- Estrategia de almacenamiento de templates:
--   El contenido HTML Mustache se guarda directamente en la columna "content"
--   (TEXT). Esto elimina la dependencia de GCS para la carga de templates y
--   permite compilación en memoria sin round-trips HTTP. El campo "gcs_key"
--   se mantiene nullable como referencia opcional al objeto original en GCS.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS data;

-- ─── Tabla: data.document_templates ──────────────────────────────────────────
-- Catálogo de templates HTML Mustache disponibles para la generación de PDFs.
-- Cada template es identificado por su "name" (único) que llega en el evento Kafka.
CREATE TABLE data.document_templates (
    id          UUID          NOT NULL DEFAULT gen_random_uuid(),
    name        VARCHAR(255)  NOT NULL,
    domain      VARCHAR(100)  NOT NULL,
    gcs_key     VARCHAR(1000),               -- Nullable: referencia histórica al objeto en GCS
    content     TEXT          NOT NULL,      -- Contenido HTML Mustache del template
    version     VARCHAR(50)   NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    description TEXT,
    created_at  TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_document_templates PRIMARY KEY (id),
    CONSTRAINT uq_document_templates_name UNIQUE (name)
);

CREATE INDEX idx_document_templates_domain ON data.document_templates (domain);
CREATE INDEX idx_document_templates_status ON data.document_templates (status);
CREATE INDEX idx_document_templates_name_status ON data.document_templates (name, status);

-- ─── Tabla: data.raw_data ────────────────────────────────────────────────────
-- Cola de trabajo del scheduler. Cada fila representa un documento a generar.
-- El scheduler procesa en lotes los registros en estado PENDING y los mueve a
-- PROCESSING → COMPLETED | FAILED según el resultado.
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

CREATE INDEX idx_raw_data_status          ON data.raw_data (status);
CREATE INDEX idx_raw_data_template_name   ON data.raw_data (template_name);
CREATE INDEX idx_raw_data_status_retry    ON data.raw_data (status, retry_count);

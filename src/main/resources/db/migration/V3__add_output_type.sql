-- ─────────────────────────────────────────────────────────────────────────────
-- V3__add_output_type.sql
-- Agrega soporte multi-formato (PDF / DOCX) a la tabla document_templates.
--
-- Cambios:
--   1. output_type VARCHAR(20) — tipo de documento a generar.
--      DEFAULT 'PDF' garantiza backward-compat con los templates existentes.
--   2. content pasa a ser nullable — los templates DOCX no usan el campo
--      content (su binario se almacena en GCS, referenciado por gcs_key).
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE data.document_templates
    ADD COLUMN output_type VARCHAR(20) NOT NULL DEFAULT 'PDF';

-- Los templates HTML Mustache existentes no tienen gcs_key, sólo content.
-- Los templates DOCX tendrán gcs_key apuntando al .docx en GCS y content=NULL.
ALTER TABLE data.document_templates
    ALTER COLUMN content DROP NOT NULL;

CREATE INDEX idx_document_templates_output_type
    ON data.document_templates (output_type);

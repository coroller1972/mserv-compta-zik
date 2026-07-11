ALTER TABLE accounting_year_settings
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD COLUMN closed_at TIMESTAMPTZ;

ALTER TABLE accounting_year_settings
    ADD CONSTRAINT ck_accounting_year_settings_status
    CHECK (status IN ('OPEN', 'REVIEWED', 'CLOSED'));

CREATE INDEX idx_accounting_year_settings_status
    ON accounting_year_settings (status);

CREATE OR REPLACE FUNCTION prevent_final_document_rewrite()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND (
        NEW.term_id IS DISTINCT FROM OLD.term_id
        OR NEW.type IS DISTINCT FROM OLD.type
        OR NEW.musician_id IS DISTINCT FROM OLD.musician_id
        OR NEW.teacher_id IS DISTINCT FROM OLD.teacher_id
        OR NEW.document_number IS DISTINCT FROM OLD.document_number
        OR NEW.file_name IS DISTINCT FROM OLD.file_name
        OR NEW.content_type IS DISTINCT FROM OLD.content_type
        OR NEW.storage_path IS DISTINCT FROM OLD.storage_path
        OR NEW.total_amount IS DISTINCT FROM OLD.total_amount
        OR NEW.metadata IS DISTINCT FROM OLD.metadata
    ) THEN
        RAISE EXCEPTION 'Finalized generated document % is immutable', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_generated_document_immutable
BEFORE UPDATE ON generated_document
FOR EACH ROW
EXECUTE FUNCTION prevent_final_document_rewrite();

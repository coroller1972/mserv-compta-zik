ALTER TABLE accounting_year_settings ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE attendance ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE music_expense ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE generated_document ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE audit_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID,
    action VARCHAR(80) NOT NULL,
    user_id VARCHAR(160) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_event_entity ON audit_event(entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_event_user ON audit_event(user_id, created_at DESC);
CREATE INDEX idx_audit_event_created_at ON audit_event(created_at DESC);

CREATE OR REPLACE FUNCTION prevent_audit_event_rewrite()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Audit events are append-only';
END;
$$;

CREATE TRIGGER trg_audit_event_immutable
BEFORE UPDATE OR DELETE ON audit_event
FOR EACH ROW EXECUTE FUNCTION prevent_audit_event_rewrite();

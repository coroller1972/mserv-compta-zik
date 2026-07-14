ALTER TABLE generated_document
    ADD COLUMN period_start_week INTEGER,
    ADD COLUMN period_end_week INTEGER,
    ADD COLUMN installment_number INTEGER;

ALTER TABLE generated_document
    ADD CONSTRAINT ck_generated_document_period
        CHECK (period_start_week IS NULL OR period_end_week IS NULL OR period_start_week <= period_end_week),
    ADD CONSTRAINT ck_generated_document_installment
        CHECK (installment_number IS NULL OR installment_number > 0);

CREATE UNIQUE INDEX uk_teacher_invoice_draft
    ON generated_document(term_id, teacher_id)
    WHERE type = 'TEACHER_INVOICE_REQUEST' AND status = 'DRAFT';

CREATE UNIQUE INDEX uk_teacher_invoice_installment
    ON generated_document(term_id, teacher_id, installment_number)
    WHERE type = 'TEACHER_INVOICE_REQUEST' AND installment_number IS NOT NULL;

CREATE TABLE teacher_invoice_attendance_allocation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES generated_document(id) ON DELETE RESTRICT,
    attendance_id UUID NOT NULL REFERENCES attendance(id) ON DELETE RESTRICT,
    released_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_active_teacher_invoice_attendance_allocation
    ON teacher_invoice_attendance_allocation(attendance_id)
    WHERE released_at IS NULL;
CREATE INDEX idx_teacher_invoice_allocation_document
    ON teacher_invoice_attendance_allocation(document_id);

CREATE TABLE teacher_billing_adjustment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    teacher_id UUID NOT NULL REFERENCES teacher(id) ON DELETE RESTRICT,
    source_document_id UUID NOT NULL REFERENCES generated_document(id) ON DELETE RESTRICT,
    applied_document_id UUID REFERENCES generated_document(id) ON DELETE RESTRICT,
    amount NUMERIC(12,2) NOT NULL CHECK (amount <> 0),
    reason VARCHAR(1000) NOT NULL CHECK (length(trim(reason)) >= 3),
    applied_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_teacher_billing_adjustment_pending
    ON teacher_billing_adjustment(teacher_id, created_at)
    WHERE applied_document_id IS NULL;

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
        OR NEW.period_start_week IS DISTINCT FROM OLD.period_start_week
        OR NEW.period_end_week IS DISTINCT FROM OLD.period_end_week
        OR NEW.installment_number IS DISTINCT FROM OLD.installment_number
    ) THEN
        RAISE EXCEPTION 'Finalized generated document % is immutable', OLD.id;
    END IF;
    RETURN NEW;
END;
$$;

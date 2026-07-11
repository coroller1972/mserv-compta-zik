ALTER TABLE generated_document
    ADD COLUMN parent_document_id UUID,
    ADD COLUMN correction_reason VARCHAR(1000),
    ADD COLUMN sent_at TIMESTAMPTZ,
    ADD COLUMN cancelled_at TIMESTAMPTZ,
    ADD COLUMN credited_at TIMESTAMPTZ;

ALTER TABLE generated_document
    ADD CONSTRAINT fk_generated_document_parent
    FOREIGN KEY (parent_document_id) REFERENCES generated_document(id) ON DELETE RESTRICT;

CREATE INDEX idx_generated_document_parent ON generated_document(parent_document_id);

ALTER TABLE generated_document DROP CONSTRAINT ck_generated_document_type;
ALTER TABLE generated_document
    ADD CONSTRAINT ck_generated_document_type
    CHECK (type IN ('STUDENT_INVOICE', 'TEACHER_INVOICE_REQUEST', 'CREDIT_NOTE'));

ALTER TABLE generated_document DROP CONSTRAINT ck_generated_document_status;
ALTER TABLE generated_document
    ADD CONSTRAINT ck_generated_document_status
    CHECK (status IN ('DRAFT', 'GENERATED', 'SENT', 'CANCELLED', 'CREDITED'));

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS accounting_year_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    year INTEGER NOT NULL,
    teacher_hourly_rate NUMERIC(10, 2) NOT NULL,
    group_membership_fee NUMERIC(10, 2) NOT NULL,
    school_holiday_weeks JSONB NOT NULL DEFAULT '{"weeks":[]}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_accounting_year_settings_year UNIQUE (year),
    CONSTRAINT ck_accounting_year_settings_year CHECK (year BETWEEN 2000 AND 2100),
    CONSTRAINT ck_accounting_year_settings_teacher_hourly_rate CHECK (teacher_hourly_rate >= 0),
    CONSTRAINT ck_accounting_year_settings_group_membership_fee CHECK (group_membership_fee >= 0)
);

CREATE TABLE IF NOT EXISTS term (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    settings_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    start_week INTEGER NOT NULL,
    end_week INTEGER NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_term_settings
        FOREIGN KEY (settings_id)
        REFERENCES accounting_year_settings (id)
        ON DELETE CASCADE,
    CONSTRAINT uk_term_settings_name UNIQUE (settings_id, name),
    CONSTRAINT ck_term_weeks CHECK (
        start_week BETWEEN 1 AND 53
        AND end_week BETWEEN 1 AND 53
        AND start_week <= end_week
    )
);

CREATE INDEX IF NOT EXISTS idx_term_settings ON term (settings_id);

CREATE TABLE IF NOT EXISTS teacher (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name VARCHAR(120) NOT NULL,
    last_name VARCHAR(120) NOT NULL,
    instrument VARCHAR(180) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX IF NOT EXISTS idx_teacher_name ON teacher (last_name, first_name);

CREATE TABLE IF NOT EXISTS musician (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name VARCHAR(120) NOT NULL,
    last_name VARCHAR(120) NOT NULL,
    email VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX IF NOT EXISTS idx_musician_name ON musician (last_name, first_name);
CREATE INDEX IF NOT EXISTS idx_musician_email ON musician (email);

CREATE TABLE IF NOT EXISTS band (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(160) NOT NULL,
    type VARCHAR(40) NOT NULL,
    teacher_id UUID,
    weekday VARCHAR(30),
    CONSTRAINT fk_band_teacher
        FOREIGN KEY (teacher_id)
        REFERENCES teacher (id)
        ON DELETE SET NULL,
    CONSTRAINT ck_band_type CHECK (type IN ('INDEPENDENT', 'WORKSHOP'))
);

CREATE INDEX IF NOT EXISTS idx_band_type ON band (type);
CREATE INDEX IF NOT EXISTS idx_band_teacher ON band (teacher_id);

CREATE TABLE IF NOT EXISTS band_member (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    band_id UUID NOT NULL,
    musician_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_band_member_band
        FOREIGN KEY (band_id)
        REFERENCES band (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_band_member_musician
        FOREIGN KEY (musician_id)
        REFERENCES musician (id)
        ON DELETE CASCADE,
    CONSTRAINT uk_band_member_band_musician UNIQUE (band_id, musician_id)
);

CREATE INDEX IF NOT EXISTS idx_band_member_band ON band_member (band_id);
CREATE INDEX IF NOT EXISTS idx_band_member_musician ON band_member (musician_id);

CREATE TABLE IF NOT EXISTS individual_course (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    musician_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    instrument VARCHAR(120) NOT NULL,
    weekday VARCHAR(30) NOT NULL,
    start_time TIME NOT NULL,
    shared_slot BOOLEAN NOT NULL DEFAULT false,
    active BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT fk_individual_course_musician
        FOREIGN KEY (musician_id)
        REFERENCES musician (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_individual_course_teacher
        FOREIGN KEY (teacher_id)
        REFERENCES teacher (id)
        ON DELETE RESTRICT,
    CONSTRAINT uk_individual_course_musician UNIQUE (musician_id)
);

CREATE INDEX IF NOT EXISTS idx_individual_course_teacher ON individual_course (teacher_id);
CREATE INDEX IF NOT EXISTS idx_individual_course_musician ON individual_course (musician_id);

CREATE TABLE IF NOT EXISTS attendance (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    term_id UUID NOT NULL,
    week INTEGER NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id UUID NOT NULL,
    present BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_attendance_term
        FOREIGN KEY (term_id)
        REFERENCES term (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_attendance_week CHECK (week BETWEEN 1 AND 53),
    CONSTRAINT ck_attendance_entity_type CHECK (entity_type IN ('INDIVIDUAL_COURSE', 'WORKSHOP')),
    CONSTRAINT uk_attendance_term_week_entity UNIQUE (term_id, week, entity_type, entity_id)
);

CREATE INDEX IF NOT EXISTS idx_attendance_term ON attendance (term_id);
CREATE INDEX IF NOT EXISTS idx_attendance_entity ON attendance (entity_type, entity_id);

CREATE TABLE IF NOT EXISTS generated_document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    term_id UUID NOT NULL,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(40) NOT NULL,
    musician_id UUID,
    teacher_id UUID,
    document_number VARCHAR(80),
    file_name VARCHAR(255),
    content_type VARCHAR(120),
    storage_path VARCHAR(500),
    total_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_generated_document_term
        FOREIGN KEY (term_id)
        REFERENCES term (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_generated_document_musician
        FOREIGN KEY (musician_id)
        REFERENCES musician (id)
        ON DELETE SET NULL,
    CONSTRAINT fk_generated_document_teacher
        FOREIGN KEY (teacher_id)
        REFERENCES teacher (id)
        ON DELETE SET NULL,
    CONSTRAINT ck_generated_document_type CHECK (type IN ('STUDENT_INVOICE', 'TEACHER_INVOICE_REQUEST')),
    CONSTRAINT ck_generated_document_status CHECK (status IN ('DRAFT', 'GENERATED', 'SENT', 'CANCELLED')),
    CONSTRAINT ck_generated_document_total_amount CHECK (total_amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_generated_document_term ON generated_document (term_id);
CREATE INDEX IF NOT EXISTS idx_generated_document_type ON generated_document (type);
CREATE INDEX IF NOT EXISTS idx_generated_document_musician ON generated_document (musician_id);
CREATE INDEX IF NOT EXISTS idx_generated_document_teacher ON generated_document (teacher_id);

INSERT INTO teacher (first_name, last_name, instrument)
SELECT 'Yann', 'DOUARINOU', 'Guitare / Basse'
WHERE NOT EXISTS (
    SELECT 1
    FROM teacher
    WHERE first_name = 'Yann'
      AND last_name = 'DOUARINOU'
      AND instrument = 'Guitare / Basse'
);

INSERT INTO teacher (first_name, last_name, instrument)
SELECT 'Mario', 'CIMENTI', 'Batterie / chant / saxophone / piano'
WHERE NOT EXISTS (
    SELECT 1
    FROM teacher
    WHERE first_name = 'Mario'
      AND last_name = 'CIMENTI'
      AND instrument = 'Batterie / chant / saxophone / piano'
);

ALTER TABLE accounting_year_settings
    ADD COLUMN individual_course_hours NUMERIC(5,2) NOT NULL DEFAULT 0.50,
    ADD COLUMN workshop_hours NUMERIC(5,2) NOT NULL DEFAULT 1.25;

ALTER TABLE accounting_year_settings
    ADD CONSTRAINT ck_accounting_year_individual_course_hours
        CHECK (individual_course_hours > 0 AND individual_course_hours <= 24),
    ADD CONSTRAINT ck_accounting_year_workshop_hours
        CHECK (workshop_hours > 0 AND workshop_hours <= 24);

-- Annualize mutable teaching configuration without recreating existing records.
-- Existing installations are based on the 2026 accounting year.

ALTER TABLE band ADD COLUMN settings_id UUID;
ALTER TABLE individual_course ADD COLUMN settings_id UUID;

UPDATE band
SET settings_id = (SELECT id FROM accounting_year_settings WHERE year = 2026)
WHERE settings_id IS NULL;

UPDATE individual_course
SET settings_id = (SELECT id FROM accounting_year_settings WHERE year = 2026)
WHERE settings_id IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM band WHERE settings_id IS NULL) THEN
        RAISE EXCEPTION 'Cannot annualize bands: accounting year 2026 is missing.';
    END IF;
    IF EXISTS (SELECT 1 FROM individual_course WHERE settings_id IS NULL) THEN
        RAISE EXCEPTION 'Cannot annualize individual courses: accounting year 2026 is missing.';
    END IF;
END $$;

ALTER TABLE band ALTER COLUMN settings_id SET NOT NULL;
ALTER TABLE individual_course ALTER COLUMN settings_id SET NOT NULL;

ALTER TABLE band
    ADD CONSTRAINT fk_band_settings
    FOREIGN KEY (settings_id)
    REFERENCES accounting_year_settings (id)
    ON DELETE RESTRICT;

ALTER TABLE individual_course
    ADD CONSTRAINT fk_individual_course_settings
    FOREIGN KEY (settings_id)
    REFERENCES accounting_year_settings (id)
    ON DELETE RESTRICT;

ALTER TABLE individual_course DROP CONSTRAINT uk_individual_course_musician;
ALTER TABLE individual_course
    ADD CONSTRAINT uk_individual_course_settings_musician
    UNIQUE (settings_id, musician_id);

CREATE INDEX idx_band_settings ON band (settings_id);
CREATE INDEX idx_individual_course_settings ON individual_course (settings_id);
CREATE INDEX idx_individual_course_settings_slot
    ON individual_course (settings_id, weekday, start_time)
    WHERE active = true;

-- La durée d'un groupe se choisit groupe par groupe (1h15 ou 1h30), plus au niveau de l'année.
-- group_hours (V10) n'a jamais été livré : on le retire.
ALTER TABLE accounting_year_settings
    DROP CONSTRAINT ck_accounting_year_group_hours,
    DROP COLUMN group_hours;

-- Null pour un atelier (durée annuelle workshop_hours) ; 75 ou 90 minutes pour un groupe, 90 par défaut.
ALTER TABLE band
    ADD COLUMN duration_minutes INTEGER;

ALTER TABLE band
    ADD CONSTRAINT ck_band_duration_minutes
        CHECK (duration_minutes IS NULL OR duration_minutes IN (75, 90));

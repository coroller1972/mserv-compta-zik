-- Planning de la salle : durée d'un groupe dans l'année et heure de début des groupes.
ALTER TABLE accounting_year_settings
    ADD COLUMN group_hours NUMERIC(5,2) NOT NULL DEFAULT 1.50;

ALTER TABLE accounting_year_settings
    ADD CONSTRAINT ck_accounting_year_group_hours
        CHECK (group_hours > 0 AND group_hours <= 24);

-- Nullable : un groupe sans heure est « à placer » et n'occupe aucun créneau.
ALTER TABLE band
    ADD COLUMN start_time TIME;

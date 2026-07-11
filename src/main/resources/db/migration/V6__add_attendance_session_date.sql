ALTER TABLE attendance ADD COLUMN session_date DATE;

WITH attendance_schedule AS (
    SELECT a.id,
           s.year,
           a.week,
           CASE
               WHEN a.entity_type = 'INDIVIDUAL_COURSE' THEN c.weekday
               WHEN a.entity_type = 'WORKSHOP' THEN b.weekday
           END AS weekday
    FROM attendance a
    JOIN term t ON t.id = a.term_id
    JOIN accounting_year_settings s ON s.id = t.settings_id
    LEFT JOIN individual_course c
        ON a.entity_type = 'INDIVIDUAL_COURSE' AND c.id = a.entity_id
    LEFT JOIN band b
        ON a.entity_type = 'WORKSHOP' AND b.id = a.entity_id
)
UPDATE attendance a
SET session_date = CASE
    WHEN schedule.week = 1 THEN make_date(schedule.year, 1, 1)
    ELSE make_date(schedule.year, 1, 1)
        + ((8 - extract(isodow FROM make_date(schedule.year, 1, 1))::integer) % 7)
        + ((schedule.week - 2) * 7)
        + CASE schedule.weekday
            WHEN 'Lundi' THEN 0
            WHEN 'Mardi' THEN 1
            WHEN 'Mercredi' THEN 2
            WHEN 'Jeudi' THEN 3
            WHEN 'Vendredi' THEN 4
            WHEN 'Samedi' THEN 5
            WHEN 'Dimanche' THEN 6
            ELSE 0
          END
END
FROM attendance_schedule schedule
WHERE schedule.id = a.id;

ALTER TABLE attendance ALTER COLUMN session_date SET NOT NULL;

ALTER TABLE attendance
    ADD CONSTRAINT uk_attendance_term_date_entity
    UNIQUE (term_id, session_date, entity_type, entity_id);

CREATE INDEX idx_attendance_session_date ON attendance(session_date);

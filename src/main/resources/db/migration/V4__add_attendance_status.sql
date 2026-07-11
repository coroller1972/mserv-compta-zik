ALTER TABLE attendance ADD COLUMN status VARCHAR(20);

UPDATE attendance
SET status = CASE WHEN present THEN 'PRESENT' ELSE 'ABSENT' END
WHERE status IS NULL;

ALTER TABLE attendance
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN status SET DEFAULT 'ABSENT';

ALTER TABLE attendance
    ADD CONSTRAINT ck_attendance_status
    CHECK (status IN ('PRESENT', 'ABSENT', 'CANCELLED'));

ALTER TABLE attendance
    ADD CONSTRAINT ck_attendance_present_status_consistent
    CHECK (present = (status = 'PRESENT'));

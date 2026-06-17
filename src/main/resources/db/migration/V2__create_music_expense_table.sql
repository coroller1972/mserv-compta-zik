CREATE TABLE IF NOT EXISTS music_expense (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    year INTEGER NOT NULL,
    expense_date DATE NOT NULL,
    category VARCHAR(60) NOT NULL,
    label VARCHAR(180) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    notes VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_music_expense_year CHECK (year BETWEEN 2000 AND 2100),
    CONSTRAINT ck_music_expense_amount CHECK (amount >= 0),
    CONSTRAINT ck_music_expense_category CHECK (
        category IN (
            'EQUIPMENT',
            'INSTRUMENT_REPAIR',
            'MAINTENANCE',
            'ROOM_RENTAL',
            'CONCERT',
            'OTHER'
        )
    )
);

CREATE INDEX IF NOT EXISTS idx_music_expense_year ON music_expense (year);
CREATE INDEX IF NOT EXISTS idx_music_expense_date ON music_expense (expense_date);

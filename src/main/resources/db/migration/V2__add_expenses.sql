CREATE TABLE IF NOT EXISTS expense (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    year INTEGER NOT NULL,
    expense_date DATE NOT NULL,
    category VARCHAR(60) NOT NULL,
    label VARCHAR(180) NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    notes VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_expense_year CHECK (year BETWEEN 2000 AND 2100),
    CONSTRAINT ck_expense_category CHECK (category IN ('EQUIPMENT', 'INSTRUMENT_REPAIR', 'MAINTENANCE', 'ROOM_RENTAL', 'CONCERT', 'OTHER')),
    CONSTRAINT ck_expense_amount CHECK (amount >= 0)
);

CREATE INDEX IF NOT EXISTS idx_expense_year ON expense (year);
CREATE INDEX IF NOT EXISTS idx_expense_date ON expense (expense_date);
CREATE INDEX IF NOT EXISTS idx_expense_category ON expense (category);

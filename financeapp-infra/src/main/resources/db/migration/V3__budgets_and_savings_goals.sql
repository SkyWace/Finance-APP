-- V2 : budgets mensuels par categorie et objectifs d'epargne.
-- Montants en unites mineures (centimes), dates ISO en TEXT, comme en V1.

CREATE TABLE budgets (
    id                   INTEGER PRIMARY KEY,
    category_id          INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    limit_minor          INTEGER NOT NULL CHECK (limit_minor > 0),
    currency             TEXT    NOT NULL DEFAULT 'EUR' CHECK (length(currency) = 3),
    period               TEXT    NOT NULL DEFAULT 'MONTHLY' CHECK (period IN ('MONTHLY')),
    reserve_in_available INTEGER NOT NULL DEFAULT 1 CHECK (reserve_in_available IN (0,1)),
    active               INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    created_at           TEXT    NOT NULL,
    updated_at           TEXT    NOT NULL
);
-- Un seul budget actif par categorie.
CREATE UNIQUE INDEX ux_budgets_active_category ON budgets (category_id) WHERE active = 1;

CREATE TABLE savings_goals (
    id                   INTEGER PRIMARY KEY,
    name                 TEXT    NOT NULL CHECK (length(trim(name)) > 0),
    target_minor         INTEGER NOT NULL CHECK (target_minor > 0),
    currency             TEXT    NOT NULL DEFAULT 'EUR' CHECK (length(currency) = 3),
    target_date          TEXT,
    linked_account_id    INTEGER REFERENCES accounts(id) ON DELETE RESTRICT,
    manual_saved_minor   INTEGER NOT NULL DEFAULT 0 CHECK (manual_saved_minor >= 0),
    reserve_in_available INTEGER NOT NULL DEFAULT 0 CHECK (reserve_in_available IN (0,1)),
    archived             INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0,1)),
    created_at           TEXT    NOT NULL,
    updated_at           TEXT    NOT NULL
);

-- Recherche par montant (valeur absolue) et par type.
CREATE INDEX ix_transactions_type_date ON transactions (type, date);

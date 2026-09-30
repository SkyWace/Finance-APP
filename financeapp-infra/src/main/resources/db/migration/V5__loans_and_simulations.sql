-- V4 : credits et simulations "What If?".

-- Le tableau d'amortissement n'est pas stocke : il est recalcule a partir de ces donnees.
CREATE TABLE loans (
    id                 INTEGER PRIMARY KEY,
    name               TEXT    NOT NULL CHECK (length(trim(name)) > 0),
    principal_minor    INTEGER NOT NULL CHECK (principal_minor > 0),
    currency           TEXT    NOT NULL,
    -- Taux nominal annuel en %, texte decimal exact (ex. "4.5") ; NULL = inconnu.
    annual_rate        TEXT,
    term_months        INTEGER NOT NULL CHECK (term_months BETWEEN 1 AND 600),
    first_payment_date TEXT    NOT NULL,
    payment_minor      INTEGER CHECK (payment_minor IS NULL OR payment_minor > 0),
    insurance_minor    INTEGER NOT NULL DEFAULT 0 CHECK (insurance_minor >= 0),
    account_id         INTEGER REFERENCES accounts(id) ON DELETE RESTRICT,
    recurring_id       INTEGER REFERENCES recurring_transactions(id) ON DELETE SET NULL,
    category_id        INTEGER REFERENCES categories(id) ON DELETE SET NULL,
    archived           INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0,1)),
    note               TEXT,
    created_at         TEXT    NOT NULL,
    updated_at         TEXT    NOT NULL,
    CHECK (annual_rate IS NOT NULL OR payment_minor IS NOT NULL)
);

CREATE UNIQUE INDEX ux_loans_recurring ON loans (recurring_id) WHERE recurring_id IS NOT NULL;

-- Scenarios : totalement separes des transactions, aucune cle vers elles.
CREATE TABLE simulations (
    id             INTEGER PRIMARY KEY,
    name           TEXT    NOT NULL CHECK (length(trim(name)) > 0),
    horizon_months INTEGER NOT NULL CHECK (horizon_months BETWEEN 1 AND 120),
    created_at     TEXT    NOT NULL,
    updated_at     TEXT    NOT NULL
);

CREATE TABLE simulation_items (
    id            INTEGER PRIMARY KEY,
    simulation_id INTEGER NOT NULL REFERENCES simulations(id) ON DELETE CASCADE,
    position      INTEGER NOT NULL,
    kind          TEXT    NOT NULL CHECK (kind IN ('ONE_TIME','MONTHLY','LOAN','STOP_RECURRING')),
    label         TEXT    NOT NULL,
    amount_minor  INTEGER,
    currency      TEXT,
    item_date     TEXT    NOT NULL,
    months        INTEGER,
    annual_rate   TEXT,
    payment_minor INTEGER,
    -- Pas de cle etrangere : une simulation doit survivre a la suppression d'une recurrence.
    recurring_id  INTEGER
);

CREATE INDEX ix_simulation_items_simulation ON simulation_items (simulation_id, position);

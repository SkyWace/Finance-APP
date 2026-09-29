-- FinanceApp - schema initial (MVP)
--
-- Conventions :
--   * montants en unites mineures (centimes) signes, type INTEGER : SQLite n'a
--     pas de type decimal exact, un REAL introduirait des erreurs d'arrondi ;
--   * dates ISO-8601 'yyyy-MM-dd' en TEXT (tri lexical = tri chronologique) ;
--   * horodatages ISO-8601 UTC en TEXT ;
--   * booleens INTEGER 0/1 ;
--   * suppressions prudentes : comptes et categories utilises sont archives,
--     jamais supprimes (ON DELETE RESTRICT).

CREATE TABLE accounts (
    id                    INTEGER PRIMARY KEY,
    name                  TEXT    NOT NULL CHECK (length(trim(name)) > 0),
    type                  TEXT    NOT NULL CHECK (type IN ('CHECKING','JOINT','PASSBOOK','SAVINGS','CASH','PREPAID_CARD','OTHER')),
    currency              TEXT    NOT NULL DEFAULT 'EUR' CHECK (length(currency) = 3),
    initial_balance_minor INTEGER NOT NULL DEFAULT 0,
    opening_date          TEXT    NOT NULL,
    icon                  TEXT,
    color                 TEXT,
    include_in_available  INTEGER NOT NULL DEFAULT 1 CHECK (include_in_available IN (0,1)),
    archived              INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0,1)),
    sort_order            INTEGER NOT NULL DEFAULT 0,
    created_at            TEXT    NOT NULL,
    updated_at            TEXT    NOT NULL
);

CREATE TABLE categories (
    id          INTEGER PRIMARY KEY,
    parent_id   INTEGER REFERENCES categories(id) ON DELETE RESTRICT,
    name        TEXT    NOT NULL CHECK (length(trim(name)) > 0),
    kind        TEXT    NOT NULL CHECK (kind IN ('EXPENSE','INCOME','BOTH')),
    icon        TEXT,
    color       TEXT,
    archived    INTEGER NOT NULL DEFAULT 0 CHECK (archived IN (0,1)),
    sort_order  INTEGER NOT NULL DEFAULT 0,
    system_code TEXT UNIQUE
);
-- Unicite (parent, nom) insensible a la casse ; ifnull car deux NULL sont distincts en SQL.
CREATE UNIQUE INDEX ux_categories_parent_name ON categories (ifnull(parent_id, 0), name COLLATE NOCASE);

CREATE TABLE recurring_transactions (
    id             INTEGER PRIMARY KEY,
    account_id     INTEGER NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    to_account_id  INTEGER REFERENCES accounts(id) ON DELETE RESTRICT,
    type           TEXT    NOT NULL CHECK (type IN ('INCOME','EXPENSE','TRANSFER')),
    label          TEXT    NOT NULL CHECK (length(trim(label)) > 0),
    amount_minor   INTEGER NOT NULL CHECK (amount_minor > 0),
    category_id    INTEGER REFERENCES categories(id) ON DELETE RESTRICT,
    frequency      TEXT    NOT NULL,
    interval_count INTEGER NOT NULL DEFAULT 1 CHECK (interval_count >= 1),
    start_date     TEXT    NOT NULL,
    end_date       TEXT,
    tracked_from   TEXT    NOT NULL,
    certain        INTEGER NOT NULL DEFAULT 1 CHECK (certain IN (0,1)),
    active         INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    note           TEXT,
    created_at     TEXT    NOT NULL,
    updated_at     TEXT    NOT NULL,
    CHECK ((type = 'TRANSFER') = (to_account_id IS NOT NULL)),
    CHECK (end_date IS NULL OR end_date >= start_date)
);

CREATE TABLE transactions (
    id                  INTEGER PRIMARY KEY,
    account_id          INTEGER NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    date                TEXT    NOT NULL,
    label               TEXT    NOT NULL CHECK (length(trim(label)) > 0),
    amount_minor        INTEGER NOT NULL CHECK (amount_minor <> 0),
    type                TEXT    NOT NULL CHECK (type IN ('INCOME','EXPENSE','TRANSFER')),
    status              TEXT    NOT NULL CHECK (status IN ('PLANNED','PENDING','COMPLETED','CANCELLED')),
    category_id         INTEGER REFERENCES categories(id) ON DELETE RESTRICT,
    note                TEXT,
    transfer_group      TEXT,
    transfer_account_id INTEGER REFERENCES accounts(id) ON DELETE RESTRICT,
    recurring_id        INTEGER REFERENCES recurring_transactions(id) ON DELETE SET NULL,
    occurrence_date     TEXT,
    created_at          TEXT    NOT NULL,
    updated_at          TEXT    NOT NULL,
    CHECK ((type = 'INCOME'  AND amount_minor > 0)
        OR (type = 'EXPENSE' AND amount_minor < 0)
        OR (type = 'TRANSFER' AND transfer_group IS NOT NULL AND transfer_account_id IS NOT NULL))
);

CREATE INDEX ix_transactions_account_date ON transactions (account_id, date);
CREATE INDEX ix_transactions_date         ON transactions (date);
CREATE INDEX ix_transactions_category     ON transactions (category_id);
CREATE INDEX ix_transactions_status_date  ON transactions (status, date);
CREATE INDEX ix_transactions_transfer     ON transactions (transfer_group) WHERE transfer_group IS NOT NULL;
-- Une occurrence de recurrence ne peut etre materialisee qu'une fois par compte.
CREATE UNIQUE INDEX ux_transactions_occurrence
    ON transactions (recurring_id, occurrence_date, account_id) WHERE recurring_id IS NOT NULL;

CREATE TABLE settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

-- V3 : import de releves, rapprochements annulables, Inbox, regles de categorisation.

CREATE TABLE import_batches (
    id          INTEGER PRIMARY KEY,
    account_id  INTEGER NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    file_name   TEXT    NOT NULL,
    format      TEXT    NOT NULL CHECK (format IN ('CSV','OFX','QIF')),
    imported_at TEXT    NOT NULL,
    created     INTEGER NOT NULL DEFAULT 0,
    reconciled  INTEGER NOT NULL DEFAULT 0,
    skipped     INTEGER NOT NULL DEFAULT 0,
    undone      INTEGER NOT NULL DEFAULT 0 CHECK (undone IN (0,1))
);

-- Operations prevues realisees par un import, avec leur etat precedent (annulation).
CREATE TABLE import_reconciliations (
    batch_id        INTEGER NOT NULL REFERENCES import_batches(id) ON DELETE CASCADE,
    transaction_id  INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    previous_status TEXT    NOT NULL,
    previous_date   TEXT    NOT NULL,
    previous_label  TEXT    NOT NULL,
    PRIMARY KEY (batch_id, transaction_id)
);

ALTER TABLE transactions ADD COLUMN import_batch_id INTEGER REFERENCES import_batches(id) ON DELETE SET NULL;
-- Identifiant fourni par la banque (FITID en OFX) : detection fiable des doublons.
ALTER TABLE transactions ADD COLUMN external_id TEXT;
-- 1 = operation importee "a valider" (Inbox).
ALTER TABLE transactions ADD COLUMN needs_review INTEGER NOT NULL DEFAULT 0 CHECK (needs_review IN (0,1));

CREATE INDEX ix_transactions_review ON transactions (needs_review) WHERE needs_review = 1;
CREATE INDEX ix_transactions_batch ON transactions (import_batch_id) WHERE import_batch_id IS NOT NULL;
CREATE UNIQUE INDEX ux_transactions_external_id ON transactions (account_id, external_id) WHERE external_id IS NOT NULL;

CREATE TABLE categorization_rules (
    id          INTEGER PRIMARY KEY,
    pattern     TEXT    NOT NULL CHECK (length(trim(pattern)) > 0),
    category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE RESTRICT,
    applies_to  TEXT    CHECK (applies_to IS NULL OR applies_to IN ('INCOME','EXPENSE')),
    active      INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0,1)),
    created_at  TEXT    NOT NULL
);

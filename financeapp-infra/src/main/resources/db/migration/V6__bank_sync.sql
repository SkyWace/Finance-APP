-- V5 (prototype) : synchronisation bancaire facultative, lecture seule.

-- 1) Les lots d'import peuvent provenir d'une synchronisation bancaire.
--    SQLite ne sait pas modifier un CHECK : la colonne est recreee (DROP COLUMN
--    n'est permis que parce que le CHECK porte sur la colonne elle-meme ; les
--    identifiants, donc les cles etrangeres, sont conserves).
ALTER TABLE import_batches ADD COLUMN format_v6 TEXT NOT NULL DEFAULT 'CSV'
    CHECK (format_v6 IN ('CSV','OFX','QIF','BANK_SYNC'));
UPDATE import_batches SET format_v6 = format;
ALTER TABLE import_batches DROP COLUMN format;
ALTER TABLE import_batches RENAME COLUMN format_v6 TO format;

-- 2) Parametres de l'application de l'utilisateur chez l'agregateur ("apportez votre cle").
--    La cle privee n'est stockee que dans cette base chiffree.
CREATE TABLE bank_sync_config (
    id              INTEGER PRIMARY KEY CHECK (id = 1),
    application_id  TEXT NOT NULL,
    private_key_pem TEXT NOT NULL,
    redirect_url    TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

-- 3) Consentements accordes aux banques (aucun identifiant bancaire).
CREATE TABLE bank_connections (
    id          INTEGER PRIMARY KEY,
    session_id  TEXT NOT NULL UNIQUE,
    bank_name   TEXT NOT NULL,
    country     TEXT NOT NULL,
    valid_until TEXT NOT NULL,
    created_at  TEXT NOT NULL
);

CREATE TABLE bank_account_links (
    id               INTEGER PRIMARY KEY,
    connection_id    INTEGER NOT NULL REFERENCES bank_connections(id) ON DELETE CASCADE,
    account_uid      TEXT    NOT NULL,
    name             TEXT    NOT NULL,
    masked_iban      TEXT,
    currency         TEXT,
    local_account_id INTEGER REFERENCES accounts(id) ON DELETE SET NULL,
    synced_until     TEXT,
    last_sync_at     TEXT,
    UNIQUE (connection_id, account_uid)
);

-- Un compte FinanceApp n'est alimente que par un seul compte bancaire.
CREATE UNIQUE INDEX ux_bank_links_local ON bank_account_links (local_account_id) WHERE local_account_id IS NOT NULL;

-- Consultations de la banque (limite reglementaire : 4 par jour et par compte).
CREATE TABLE bank_sync_fetches (
    link_id    INTEGER NOT NULL REFERENCES bank_account_links(id) ON DELETE CASCADE,
    fetched_at INTEGER NOT NULL
);

CREATE INDEX ix_bank_sync_fetches ON bank_sync_fetches (link_id, fetched_at);

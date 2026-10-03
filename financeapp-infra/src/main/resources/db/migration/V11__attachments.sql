-- V0.3.0 : justificatifs joints aux operations (factures, tickets...). Le contenu est
-- dans la base chiffree : chiffre, sauvegarde et restaure avec elle. Supprime avec
-- l'operation.
CREATE TABLE attachments (
    id             INTEGER PRIMARY KEY,
    transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    file_name      TEXT NOT NULL CHECK (length(trim(file_name)) > 0),
    media_type     TEXT NOT NULL,
    size_bytes     INTEGER NOT NULL CHECK (size_bytes > 0),
    added_at       TEXT NOT NULL,
    content        BLOB NOT NULL
);
CREATE INDEX ix_attachments_transaction ON attachments (transaction_id);

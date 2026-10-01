-- V0.2.0 : ventilation d'une operation sur plusieurs categories, etiquettes.

-- Lignes de ventilation : la somme des montants est egale au montant de l'operation
-- (verifie par l'application) ; transactions.category_id est alors vide.
CREATE TABLE transaction_splits (
    id             INTEGER PRIMARY KEY,
    transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    position       INTEGER NOT NULL,
    category_id    INTEGER REFERENCES categories(id) ON DELETE RESTRICT,
    amount_minor   INTEGER NOT NULL CHECK (amount_minor <> 0),
    UNIQUE (transaction_id, position)
);
CREATE INDEX ix_transaction_splits_category ON transaction_splits (category_id);

CREATE TABLE tags (
    id         INTEGER PRIMARY KEY,
    name       TEXT NOT NULL COLLATE NOCASE UNIQUE CHECK (length(trim(name)) > 0),
    created_at TEXT NOT NULL
);

CREATE TABLE transaction_tags (
    transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    tag_id         INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (transaction_id, tag_id)
);
CREATE INDEX ix_transaction_tags_tag ON transaction_tags (tag_id);

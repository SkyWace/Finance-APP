-- V0.2.3 : ventilation des operations recurrentes. Montants positifs (le sens est celui
-- de la regle), somme egale au montant de la regle (verifie par l'application) ;
-- recurring_transactions.category_id est alors vide.
CREATE TABLE recurring_splits (
    id           INTEGER PRIMARY KEY,
    rule_id      INTEGER NOT NULL REFERENCES recurring_transactions(id) ON DELETE CASCADE,
    position     INTEGER NOT NULL,
    category_id  INTEGER REFERENCES categories(id) ON DELETE RESTRICT,
    amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
    UNIQUE (rule_id, position)
);
CREATE INDEX ix_recurring_splits_category ON recurring_splits (category_id);

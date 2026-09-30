-- Epargne detenue : types de produits d'epargne et valorisations.

-- 1) Nouveaux types de comptes (Livret A, LDDS, LEP, PEL, assurance-vie, PEA, PER,
--    epargne salariale...). SQLite ne modifie pas un CHECK : la colonne est recreee
--    (identifiants et cles etrangeres conserves).
ALTER TABLE accounts ADD COLUMN type_v7 TEXT NOT NULL DEFAULT 'OTHER'
    CHECK (type_v7 IN ('CHECKING','JOINT','CASH','PREPAID_CARD',
                       'LIVRET_A','LDDS','LEP','LIVRET_JEUNE','CEL','PASSBOOK','SAVINGS',
                       'PEL','LIFE_INSURANCE','PEA','PER','EMPLOYEE_SAVINGS','SECURITIES','CRYPTO',
                       'OTHER'));
UPDATE accounts SET type_v7 = type;
ALTER TABLE accounts DROP COLUMN type;
ALTER TABLE accounts RENAME COLUMN type_v7 TO type;

-- 2) Valeurs constatees (releve d'un livret, valeur d'un PEA...) : le solde devient la
--    derniere valeur plus les operations posterieures. Une par compte et par date.
CREATE TABLE account_valuations (
    id          INTEGER PRIMARY KEY,
    account_id  INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    value_date  TEXT    NOT NULL,
    value_minor INTEGER NOT NULL CHECK (value_minor >= 0),
    currency    TEXT    NOT NULL,
    created_at  TEXT    NOT NULL,
    UNIQUE (account_id, value_date)
);

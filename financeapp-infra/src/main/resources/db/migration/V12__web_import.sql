-- V12 : imports depuis la version web (sauvegarde du site) : nouveau format de lot.
-- SQLite ne modifie pas une contrainte CHECK : la colonne est recreee (meme methode qu'en V6).
ALTER TABLE import_batches ADD COLUMN format_v12 TEXT NOT NULL DEFAULT 'CSV'
    CHECK (format_v12 IN ('CSV','OFX','QIF','BANK_SYNC','WEB'));
UPDATE import_batches SET format_v12 = format;
ALTER TABLE import_batches DROP COLUMN format;
ALTER TABLE import_batches RENAME COLUMN format_v12 TO format;

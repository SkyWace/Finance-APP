package com.financeapp.infra.db;

import org.springframework.jdbc.support.KeyHolder;

final class JdbcKeys {

    private JdbcKeys() {
    }

    /** Identifiant genere par SQLite (rowid) ; le pilote l'expose sous un nom de colonne variable. */
    static long id(KeyHolder keys) {
        Number key = keys.getKeyList().isEmpty() ? null
                : (Number) keys.getKeyList().getFirst().values().iterator().next();
        if (key == null) {
            throw new IllegalStateException("Aucun identifiant genere");
        }
        return key.longValue();
    }
}

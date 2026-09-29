package com.financeapp.infra.security;

import java.sql.SQLException;

/** Acces a la base refuse : l'application est verrouillee (cle effacee de la memoire). */
public class DatabaseLockedException extends SQLException {

    public DatabaseLockedException() {
        super("Application verrouillee");
    }
}

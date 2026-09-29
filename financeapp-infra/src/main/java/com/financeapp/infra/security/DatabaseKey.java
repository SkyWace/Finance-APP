package com.financeapp.infra.security;

import java.util.Arrays;

/**
 * Cle de la base pendant la session. Verrouiller efface la cle de la memoire
 * (tableau remis a zero) : plus aucune connexion ne peut etre ouverte tant
 * que l'utilisateur n'a pas ressaisi son mot de passe.
 */
public final class DatabaseKey {

    private byte[] key;

    public synchronized void unlock(byte[] dek) {
        if (dek == null || dek.length != KeyDerivation.KEY_BYTES) {
            throw new IllegalArgumentException("Cle invalide");
        }
        clear();
        key = dek.clone();
    }

    public synchronized void lock() {
        clear();
    }

    public synchronized boolean isUnlocked() {
        return key != null;
    }

    /** Copie de la cle, a effacer par l'appelant apres usage. */
    public synchronized byte[] copy() throws DatabaseLockedException {
        if (key == null) {
            throw new DatabaseLockedException();
        }
        return key.clone();
    }

    private void clear() {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
            key = null;
        }
    }
}

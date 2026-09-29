package com.financeapp.infra.backup;

/** Le fichier fourni n'est pas une sauvegarde FinanceApp exploitable ; le message est destine a l'utilisateur. */
public class InvalidBackupException extends Exception {

    public InvalidBackupException(String message) {
        super(message);
    }

    public InvalidBackupException(String message, Throwable cause) {
        super(message, cause);
    }
}

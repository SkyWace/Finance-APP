package com.financeapp.infra.security;

/** Mot de passe ou cle de recuperation incorrect (ou trousseau altere). */
public class InvalidSecretException extends Exception {

    public InvalidSecretException(String message) {
        super(message);
    }
}

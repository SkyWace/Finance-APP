package com.financeapp.core.banksync;

import java.util.Objects;

/**
 * Parametres de l'application enregistree par l'utilisateur chez l'agregateur
 * ("apportez votre cle"). La cle privee ne quitte jamais la base chiffree ;
 * {@link #toString()} ne la revele pas.
 *
 * @param redirectUrl adresse de retour declaree chez l'agregateur (HTTPS en production)
 */
public record BankSyncCredentials(String applicationId, String privateKeyPem, String redirectUrl) {

    public BankSyncCredentials {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(privateKeyPem, "privateKeyPem");
        Objects.requireNonNull(redirectUrl, "redirectUrl");
        applicationId = applicationId.strip();
        redirectUrl = redirectUrl.strip();
    }

    @Override
    public String toString() {
        return "BankSyncCredentials[applicationId=" + applicationId + ", privateKey=***, redirectUrl=" + redirectUrl + "]";
    }
}

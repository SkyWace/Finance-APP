package com.financeapp.core.banksync;

/**
 * Compte bancaire autorise par l'utilisateur.
 *
 * @param uid        identifiant du compte chez l'agregateur (valable pour la session)
 * @param maskedIban IBAN masque pour l'affichage (ex. "FR76 •••• 1234"), jamais l'IBAN complet
 */
public record RemoteAccount(String uid, String name, String maskedIban, String currency) {
}

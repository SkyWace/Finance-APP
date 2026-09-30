package com.financeapp.core.banksync;

/**
 * Banque accessible via l'agregateur.
 *
 * @param maxConsentDays duree maximale du consentement acceptee par la banque (souvent 180 jours)
 */
public record BankInfo(String name, String country, int maxConsentDays, boolean beta) {
}

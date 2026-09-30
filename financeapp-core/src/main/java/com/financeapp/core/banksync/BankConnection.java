package com.financeapp.core.banksync;

import java.time.Instant;

/** Connexion enregistree a une banque (un consentement). */
public record BankConnection(Long id, String sessionId, String bankName, String country, Instant validUntil,
                             Instant createdAt) {

    public BankConnection withId(long newId) {
        return new BankConnection(newId, sessionId, bankName, country, validUntil, createdAt);
    }

    public boolean expired(Instant now) {
        return !validUntil.isAfter(now);
    }
}

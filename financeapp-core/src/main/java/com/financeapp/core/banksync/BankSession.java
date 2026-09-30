package com.financeapp.core.banksync;

import java.time.Instant;
import java.util.List;

/** Consentement accorde par l'utilisateur a sa banque (lecture seule), avec les comptes autorises. */
public record BankSession(String sessionId, String bankName, String country, Instant validUntil,
                          List<RemoteAccount> accounts) {

    public BankSession {
        accounts = List.copyOf(accounts);
    }
}

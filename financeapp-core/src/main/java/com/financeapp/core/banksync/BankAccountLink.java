package com.financeapp.core.banksync;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Compte bancaire autorise, et le compte FinanceApp auquel il est associe.
 *
 * @param localAccountId   compte FinanceApp alimente ({@code null} tant que l'utilisateur n'a pas choisi)
 * @param syncedUntil      date de la derniere operation comptabilisee importee
 * @param lastSyncAt       derniere recuperation
 */
public record BankAccountLink(Long id, long connectionId, String accountUid, String name, String maskedIban,
                              String currency, Long localAccountId, LocalDate syncedUntil, Instant lastSyncAt) {

    public BankAccountLink withId(long newId) {
        return new BankAccountLink(newId, connectionId, accountUid, name, maskedIban, currency, localAccountId,
                syncedUntil, lastSyncAt);
    }

    public BankAccountLink withLocalAccount(Long accountId) {
        return new BankAccountLink(id, connectionId, accountUid, name, maskedIban, currency, accountId, syncedUntil,
                lastSyncAt);
    }

    public BankAccountLink withSync(LocalDate until, Instant at) {
        return new BankAccountLink(id, connectionId, accountUid, name, maskedIban, currency, localAccountId, until, at);
    }
}

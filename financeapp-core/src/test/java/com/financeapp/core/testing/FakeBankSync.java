package com.financeapp.core.testing;

import com.financeapp.core.banksync.BankInfo;
import com.financeapp.core.banksync.BankSession;
import com.financeapp.core.banksync.BankSyncCredentials;
import com.financeapp.core.banksync.RemoteAccount;
import com.financeapp.core.banksync.RemoteTransaction;
import com.financeapp.core.port.BankSyncClient;
import com.financeapp.core.port.BankSyncClientFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Agregateur simule : banques, sessions et operations scriptees par le test. */
public final class FakeBankSync implements BankSyncClientFactory, BankSyncClient {

    public final List<RemoteTransaction> transactions = new ArrayList<>();
    public final List<String> deletedSessions = new ArrayList<>();
    public final List<LocalDate> requestedFrom = new ArrayList<>();
    public List<RemoteAccount> accounts = List.of(new RemoteAccount("uid-1", "Compte chèque", "FR76 •••• 0001", "EUR"));
    public Instant sessionValidUntil;
    public String lastState;
    public String lastRedirect;
    public Instant lastValidUntil;
    public String expectedCode = "code-123";

    @Override
    public BankSyncClient create(BankSyncCredentials credentials) {
        if (credentials.privateKeyPem().contains("INVALID")) {
            throw new IllegalArgumentException("Clé privée illisible");
        }
        return this;
    }

    @Override
    public String providerName() {
        return "Banque simulée";
    }

    @Override
    public List<BankInfo> banks(String country) {
        return List.of(new BankInfo("Zeta Banque", country, 180, false), new BankInfo("Alpha Banque", country, 90, false));
    }

    @Override
    public String startAuthorization(BankInfo bank, String redirectUrl, String state, Instant validUntil) {
        lastState = state;
        lastRedirect = redirectUrl;
        lastValidUntil = validUntil;
        return "https://bank.example/authorize?state=" + state;
    }

    @Override
    public BankSession createSession(String code) {
        if (!expectedCode.equals(code)) {
            throw new IllegalStateException("Code d'autorisation refusé");
        }
        return new BankSession("session-1", "Zeta Banque", "FR", sessionValidUntil, accounts);
    }

    @Override
    public List<RemoteTransaction> transactions(String accountUid, LocalDate from) {
        requestedFrom.add(from);
        return transactions.stream().filter(t -> t.date() == null || !t.date().isBefore(from)).toList();
    }

    @Override
    public void deleteSession(String sessionId) {
        deletedSessions.add(sessionId);
    }
}

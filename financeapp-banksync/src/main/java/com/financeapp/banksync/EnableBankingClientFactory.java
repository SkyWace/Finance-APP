package com.financeapp.banksync;

import com.financeapp.core.banksync.BankSyncCredentials;
import com.financeapp.core.port.BankSyncClient;
import com.financeapp.core.port.BankSyncClientFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/** Fabrique du client Enable Banking a partir des parametres de l'utilisateur. */
public final class EnableBankingClientFactory implements BankSyncClientFactory {

    private final URI base;
    private final HttpClient http;
    private final Clock clock;

    /** Production : {@link EnableBankingClient#PRODUCTION}. */
    public EnableBankingClientFactory(Clock clock) {
        this(EnableBankingClient.PRODUCTION, clock);
    }

    /** @param base autre adresse d'API (serveur simule sur la boucle locale uniquement) */
    public EnableBankingClientFactory(URI base, Clock clock) {
        this.base = EnableBankingClient.checkedBase(base);
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public BankSyncClient create(BankSyncCredentials credentials) {
        if (!credentials.applicationId().matches("[A-Za-z0-9-]{8,64}")) {
            throw new IllegalArgumentException("Identifiant d'application invalide (format attendu : "
                    + "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee)");
        }
        return new EnableBankingClient(base, credentials.applicationId(),
                PemKeys.readPrivateKey(credentials.privateKeyPem()), http, clock);
    }

    @Override
    public String providerName() {
        return "Enable Banking (Finlande)";
    }

    public URI base() {
        return base;
    }
}

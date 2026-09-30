package com.financeapp.core.port;

import com.financeapp.core.banksync.BankSyncCredentials;

/** Construit un client a partir des parametres de l'utilisateur ; refuse une cle invalide. */
public interface BankSyncClientFactory {

    /** @throws IllegalArgumentException si la cle ou l'identifiant sont inutilisables */
    BankSyncClient create(BankSyncCredentials credentials);

    /** Nom et pays de l'agregateur, affiches a l'utilisateur avant activation. */
    String providerName();
}

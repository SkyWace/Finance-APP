package com.financeapp.core.port;

import com.financeapp.core.banksync.BankAccountLink;
import com.financeapp.core.banksync.BankConnection;
import com.financeapp.core.banksync.BankSyncCredentials;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Parametres, connexions et comptes lies, dans la base chiffree. */
public interface BankSyncRepository {

    Optional<BankSyncCredentials> credentials();

    void saveCredentials(BankSyncCredentials credentials);

    /** Efface parametres, connexions et comptes lies (desactivation). */
    void clearAll();

    List<BankConnection> connections();

    /** Enregistre une connexion et ses comptes en une fois. */
    BankConnection saveConnection(BankConnection connection, List<BankAccountLink> accounts);

    void deleteConnection(long connectionId);

    List<BankAccountLink> links();

    Optional<BankAccountLink> link(long id);

    BankAccountLink saveLink(BankAccountLink link);

    /** Trace une consultation de la banque pour ce compte (limite DSP2 : 4 par jour sans l'utilisateur). */
    void recordFetch(long linkId, Instant at);

    int fetchesSince(long linkId, Instant since);
}

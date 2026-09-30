package com.financeapp.core.port;

import com.financeapp.core.banksync.BankInfo;
import com.financeapp.core.banksync.BankSession;
import com.financeapp.core.banksync.RemoteTransaction;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Acces en lecture seule a un agregateur agree (AISP). Aucun identifiant
 * bancaire ne transite ici : l'utilisateur s'authentifie chez sa banque.
 * Les implementations levent {@link IllegalStateException} avec un message
 * destine a l'utilisateur (sans donnees financieres) en cas d'echec.
 */
public interface BankSyncClient {

    List<BankInfo> banks(String country);

    /** Adresse de la banque ou l'utilisateur donne son accord (a ouvrir dans le navigateur). */
    String startAuthorization(BankInfo bank, String redirectUrl, String state, Instant validUntil);

    /** Echange le code recu a l'adresse de retour contre une session. */
    BankSession createSession(String code);

    List<RemoteTransaction> transactions(String accountUid, LocalDate from);

    void deleteSession(String sessionId);
}

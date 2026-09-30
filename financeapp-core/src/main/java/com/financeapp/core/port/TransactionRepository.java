package com.financeapp.core.port;

import com.financeapp.core.transaction.Transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface TransactionRepository {

    Transaction insert(Transaction transaction);

    /**
     * Insere les deux jambes d'un virement de maniere atomique (tout ou rien).
     * Elles doivent partager le meme {@code transferGroup}.
     */
    List<Transaction> insertAll(List<Transaction> transactions);

    Transaction update(Transaction transaction);

    /** Met a jour plusieurs lignes de maniere atomique (jambes d'un virement). */
    void updateAll(List<Transaction> transactions);

    void delete(long id);

    void deleteTransferGroup(String transferGroup);

    Optional<Transaction> findById(long id);

    List<Transaction> findByTransferGroup(String transferGroup);

    List<Transaction> search(TransactionQuery query);

    /**
     * Totaux sur l'ensemble des resultats de la requete (sans pagination), pour les
     * comptes de la devise donnee ; operations annulees et virements internes exclus.
     */
    SearchTotals summarize(TransactionQuery query, java.util.Currency currency);

    /** Somme, en unites mineures, des operations comptees dans le solde, par compte. */
    Map<Long, Long> sumCountedMinorByAccount();

    /** Somme des operations comptees d'un compte datees strictement apres {@code after}. */
    long sumCountedMinorAfter(long accountId, LocalDate after);

    /** Operations comptees dans le solde (effectuees + en attente) dont la date est dans [from, to]. */
    List<Transaction> findCounted(LocalDate from, LocalDate to);

    /** Toutes les operations au statut "prevu" dont la date est au plus {@code until}, retard compris. */
    List<Transaction> findPlannedUntil(LocalDate until);

    /** Occurrences de regles recurrentes deja materialisees a partir de {@code from}. */
    Set<OccurrenceKey> findMaterializedOccurrences(LocalDate from);

    boolean existsForAccount(long accountId);

    /** Operations importees en attente de validation (Inbox), les plus anciennes d'abord. */
    List<Transaction> findNeedingReview();

    long countNeedingReview();

    /** Retire l'operation de l'Inbox (categorie eventuellement mise a jour au prealable). */
    void markReviewed(long id);
}

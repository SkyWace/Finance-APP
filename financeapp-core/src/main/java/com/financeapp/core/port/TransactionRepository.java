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

    /** Somme, en unites mineures, des operations comptees dans le solde, par compte. */
    Map<Long, Long> sumCountedMinorByAccount();

    /** Operations comptees dans le solde (effectuees + en attente) dont la date est dans [from, to]. */
    List<Transaction> findCounted(LocalDate from, LocalDate to);

    /** Toutes les operations au statut "prevu" dont la date est au plus {@code until}, retard compris. */
    List<Transaction> findPlannedUntil(LocalDate until);

    /** Occurrences de regles recurrentes deja materialisees a partir de {@code from}. */
    Set<OccurrenceKey> findMaterializedOccurrences(LocalDate from);

    boolean existsForAccount(long accountId);
}

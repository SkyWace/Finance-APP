package com.financeapp.core.service;

import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Saisie d'un revenu ou d'une depense. Le montant est saisi en valeur
 * absolue ; le signe est deduit du type, la devise du compte.
 *
 * @param splits ventilation (vide : une seule categorie, {@code categoryId}) ;
 *               montants en valeur absolue, leur somme egale au montant
 * @param tagIds etiquettes
 */
public record TransactionDraft(
        long accountId,
        LocalDate date,
        String label,
        BigDecimal amount,
        TransactionType type,
        TransactionStatus status,
        Long categoryId,
        String note,
        List<Split> splits,
        Set<Long> tagIds) {

    /** Ligne de ventilation saisie (montant en valeur absolue). */
    public record Split(Long categoryId, BigDecimal amount) {
    }

    public TransactionDraft(long accountId, LocalDate date, String label, BigDecimal amount, TransactionType type,
                            TransactionStatus status, Long categoryId, String note) {
        this(accountId, date, label, amount, type, status, categoryId, note, List.of(), Set.of());
    }

    public TransactionDraft {
        splits = splits == null ? List.of() : List.copyOf(splits);
        tagIds = tagIds == null ? Set.of() : Set.copyOf(tagIds);
    }
}

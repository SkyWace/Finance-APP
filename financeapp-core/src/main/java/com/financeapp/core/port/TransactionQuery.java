package com.financeapp.core.port;

import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * Criteres de recherche de transactions. Tous les champs sont optionnels
 * ({@code null} = pas de filtre). Resultats tries par date decroissante.
 *
 * @param text       recherche insensible a la casse dans le libelle et le commentaire
 * @param categoryId categorie, sous-categories comprises
 * @param minAmount  montant minimal en valeur absolue (inclus)
 * @param maxAmount  montant maximal en valeur absolue (inclus)
 * @param limit      nombre maximal de lignes (pagination)
 * @param tagId      etiquette
 */
public record TransactionQuery(
        Long accountId,
        LocalDate from,
        LocalDate to,
        String text,
        Long categoryId,
        Set<TransactionStatus> statuses,
        TransactionType type,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        int limit,
        int offset,
        Long tagId) {

    public TransactionQuery(Long accountId, LocalDate from, LocalDate to, String text, Long categoryId,
                            Set<TransactionStatus> statuses, int limit, int offset) {
        this(accountId, from, to, text, categoryId, statuses, null, null, null, limit, offset, null);
    }

    public TransactionQuery(Long accountId, LocalDate from, LocalDate to, String text, Long categoryId,
                            Set<TransactionStatus> statuses, TransactionType type, BigDecimal minAmount,
                            BigDecimal maxAmount, int limit, int offset) {
        this(accountId, from, to, text, categoryId, statuses, type, minAmount, maxAmount, limit, offset, null);
    }

    public static TransactionQuery all() {
        return new TransactionQuery(null, null, null, null, null, null, 500, 0);
    }

    public TransactionQuery withLimit(int newLimit) {
        return new TransactionQuery(accountId, from, to, text, categoryId, statuses, type, minAmount, maxAmount,
                newLimit, offset, tagId);
    }

    public TransactionQuery withTag(Long newTagId) {
        return new TransactionQuery(accountId, from, to, text, categoryId, statuses, type, minAmount, maxAmount,
                limit, offset, newTagId);
    }
}

package com.financeapp.core.port;

import com.financeapp.core.transaction.TransactionStatus;

import java.time.LocalDate;
import java.util.Set;

/**
 * Criteres de recherche de transactions. Tous les champs sont optionnels
 * ({@code null} = pas de filtre). Resultats tries par date decroissante.
 *
 * @param text   recherche insensible a la casse dans le libelle et le commentaire
 * @param limit  nombre maximal de lignes (pagination)
 */
public record TransactionQuery(
        Long accountId,
        LocalDate from,
        LocalDate to,
        String text,
        Long categoryId,
        Set<TransactionStatus> statuses,
        int limit,
        int offset) {

    public static TransactionQuery all() {
        return new TransactionQuery(null, null, null, null, null, null, 500, 0);
    }

    public TransactionQuery withLimit(int newLimit) {
        return new TransactionQuery(accountId, from, to, text, categoryId, statuses, newLimit, offset);
    }
}

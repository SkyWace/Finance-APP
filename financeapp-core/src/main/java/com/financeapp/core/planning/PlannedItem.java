package com.financeapp.core.planning;

import com.financeapp.core.money.Money;
import com.financeapp.core.transaction.TransactionType;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Operation a venir, quelle que soit son origine : transaction saisie avec le
 * statut "prevu", ou occurrence calculee d'une regle recurrente. C'est
 * l'entree commune du disponible reel et des previsions.
 *
 * @param amount            signe dans la perspective de {@code accountId}
 * @param transferAccountId autre compte d'un virement ({@code null} sinon)
 * @param transactionId     renseigne si {@code source == PLANNED_TRANSACTION}
 * @param recurringId       renseigne si {@code source == RECURRING}
 * @param certain           revenu juge suffisamment certain
 * @param splits            ventilation signee comme {@code amount} (vide : une seule categorie)
 */
public record PlannedItem(
        LocalDate date,
        long accountId,
        String label,
        Money amount,
        TransactionType type,
        Long categoryId,
        Source source,
        Long transactionId,
        Long recurringId,
        Long transferAccountId,
        boolean certain,
        java.util.List<com.financeapp.core.transaction.SplitLine> splits) {

    public PlannedItem(LocalDate date, long accountId, String label, Money amount, TransactionType type, Long categoryId,
                       Source source, Long transactionId, Long recurringId, Long transferAccountId, boolean certain) {
        this(date, accountId, label, amount, type, categoryId, source, transactionId, recurringId, transferAccountId,
                certain, java.util.List.of());
    }

    public enum Source { PLANNED_TRANSACTION, RECURRING }

    public PlannedItem {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        splits = splits == null ? java.util.List.of() : java.util.List.copyOf(splits);
    }

    /** Repartition par categorie : la ventilation, ou une part unique. */
    public java.util.List<com.financeapp.core.transaction.SplitLine> categoryShares() {
        return splits.isEmpty() ? java.util.List.of(new com.financeapp.core.transaction.SplitLine(categoryId, amount)) : splits;
    }

    public boolean isOverdue(LocalDate today) {
        return date.isBefore(today);
    }
}

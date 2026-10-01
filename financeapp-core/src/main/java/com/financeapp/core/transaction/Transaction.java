package com.financeapp.core.transaction;

import com.financeapp.core.money.Money;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Ligne d'operation sur un compte.
 *
 * <p>Le montant est <b>signe dans la perspective du compte</b> : negatif
 * quand l'argent sort, positif quand il entre. Un virement interne est
 * represente par deux lignes {@link TransactionType#TRANSFER} partageant le
 * meme {@code transferGroup}, une par compte, de signes opposes.
 *
 * @param transferAccountId compte de l'autre cote d'un virement ({@code null} sinon)
 * @param recurringId       regle recurrente dont cette ligne materialise une occurrence
 * @param occurrenceDate    date theorique de l'occurrence materialisee
 * @param splits            ventilation sur plusieurs categories (vide : une seule categorie,
 *                          {@code categoryId}) ; quand elle existe, {@code categoryId} est vide
 * @param tagIds            etiquettes de l'operation
 */
public record Transaction(
        Long id,
        long accountId,
        LocalDate date,
        String label,
        Money amount,
        TransactionType type,
        TransactionStatus status,
        Long categoryId,
        String note,
        String transferGroup,
        Long transferAccountId,
        Long recurringId,
        LocalDate occurrenceDate,
        List<SplitLine> splits,
        Set<Long> tagIds) {

    /** Operation sans ventilation ni etiquette. */
    public Transaction(Long id, long accountId, LocalDate date, String label, Money amount, TransactionType type,
                       TransactionStatus status, Long categoryId, String note, String transferGroup,
                       Long transferAccountId, Long recurringId, LocalDate occurrenceDate) {
        this(id, accountId, date, label, amount, type, status, categoryId, note, transferGroup, transferAccountId,
                recurringId, occurrenceDate, List.of(), Set.of());
    }

    public Transaction {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Le libellé est obligatoire");
        }
        label = label.strip();
        if (amount.isZero()) {
            throw new IllegalArgumentException("Le montant ne peut pas être nul");
        }
        switch (type) {
            case INCOME -> {
                if (amount.isNegative()) throw new IllegalArgumentException("Un revenu doit être positif");
            }
            case EXPENSE -> {
                if (amount.isPositive()) throw new IllegalArgumentException("Une dépense doit être négative");
            }
            case TRANSFER -> {
                if (transferGroup == null || transferAccountId == null) {
                    throw new IllegalArgumentException("Un virement doit être lié à un compte de destination");
                }
                if (transferAccountId == accountId) {
                    throw new IllegalArgumentException("Un virement doit relier deux comptes différents");
                }
            }
        }
        if ((recurringId == null) != (occurrenceDate == null)) {
            throw new IllegalArgumentException("recurringId et occurrenceDate vont ensemble");
        }
        splits = splits == null ? List.of() : List.copyOf(splits);
        tagIds = tagIds == null ? Set.of() : Set.copyOf(tagIds);
        if (!splits.isEmpty()) {
            validateSplits(splits, amount, type);
            categoryId = null; // la categorie est portee par chaque ligne
        }
    }

    private static void validateSplits(List<SplitLine> splits, Money amount, TransactionType type) {
        if (type == TransactionType.TRANSFER) {
            throw new IllegalArgumentException("Un virement interne ne se ventile pas");
        }
        if (splits.size() < 2) {
            throw new IllegalArgumentException("Une ventilation comporte au moins deux lignes");
        }
        Money total = Money.zero(amount.currency());
        for (SplitLine line : splits) {
            if (!line.amount().isSameCurrency(amount)) {
                throw new IllegalArgumentException("Les lignes de ventilation doivent être dans la devise de l'opération");
            }
            if (line.amount().signum() != amount.signum()) {
                throw new IllegalArgumentException("Chaque ligne de ventilation doit être du même sens que l'opération");
            }
            total = total.plus(line.amount());
        }
        if (!total.equals(amount)) {
            throw new IllegalArgumentException("La somme des lignes de ventilation doit être égale au montant de l'opération");
        }
    }

    public boolean isSplit() {
        return !splits.isEmpty();
    }

    /**
     * Repartition de l'operation par categorie : ses lignes de ventilation, ou une
     * seule part (sa categorie, tout son montant). Base de tous les totaux par categorie.
     */
    public List<SplitLine> categoryShares() {
        return isSplit() ? splits : List.of(new SplitLine(categoryId, amount));
    }

    public boolean isTransfer() {
        return type == TransactionType.TRANSFER;
    }

    public Transaction withId(long newId) {
        return new Transaction(newId, accountId, date, label, amount, type, status, categoryId, note,
                transferGroup, transferAccountId, recurringId, occurrenceDate, splits, tagIds);
    }

    public Transaction withStatus(TransactionStatus newStatus) {
        return new Transaction(id, accountId, date, label, amount, type, newStatus, categoryId, note,
                transferGroup, transferAccountId, recurringId, occurrenceDate, splits, tagIds);
    }

    /** Une seule categorie : la ventilation eventuelle est retiree, les etiquettes conservees. */
    public Transaction withCategory(Long newCategoryId) {
        return new Transaction(id, accountId, date, label, amount, type, status, newCategoryId, note,
                transferGroup, transferAccountId, recurringId, occurrenceDate, List.of(), tagIds);
    }

    /** Ventilation et etiquettes lues en base (rattachees apres la ligne principale). */
    public Transaction withDetails(List<SplitLine> newSplits, Set<Long> newTagIds) {
        return new Transaction(id, accountId, date, label, amount, type, status, newSplits.isEmpty() ? categoryId : null,
                note, transferGroup, transferAccountId, recurringId, occurrenceDate, newSplits, newTagIds);
    }
}

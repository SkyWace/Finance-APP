package com.financeapp.core.transaction;

import com.financeapp.core.money.Money;

import java.time.LocalDate;
import java.util.Objects;

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
        LocalDate occurrenceDate) {

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
    }

    public boolean isTransfer() {
        return type == TransactionType.TRANSFER;
    }

    public Transaction withId(long newId) {
        return new Transaction(newId, accountId, date, label, amount, type, status, categoryId, note,
                transferGroup, transferAccountId, recurringId, occurrenceDate);
    }

    public Transaction withStatus(TransactionStatus newStatus) {
        return new Transaction(id, accountId, date, label, amount, type, newStatus, categoryId, note,
                transferGroup, transferAccountId, recurringId, occurrenceDate);
    }
}

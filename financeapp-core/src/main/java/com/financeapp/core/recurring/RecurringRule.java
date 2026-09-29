package com.financeapp.core.recurring;

import com.financeapp.core.money.Money;
import com.financeapp.core.transaction.TransactionType;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Regle d'operation recurrente. Ses occurrences ne sont PAS stockees : elles
 * sont calculees a la demande par {@link RecurrenceEngine}. Seules les
 * occurrences validees (ou ignorees) deviennent des transactions.
 *
 * @param amount      montant toujours positif ; le sens est donne par {@code type}
 * @param toAccountId compte destinataire pour un virement recurrent (ex. epargne mensuelle)
 * @param certain     revenu juge suffisamment certain pour etre compte dans le disponible reel
 * @param endDate     derniere date possible (incluse), {@code null} = sans fin
 * @param trackedFrom date a partir de laquelle les occurrences sont suivies (en general la date de
 *                    creation de la regle) : les occurrences anterieures sont reputees deja traitees,
 *                    elles n'apparaissent jamais comme "en retard"
 */
public record RecurringRule(
        Long id,
        long accountId,
        Long toAccountId,
        TransactionType type,
        String label,
        Money amount,
        Long categoryId,
        Frequency frequency,
        int interval,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate trackedFrom,
        boolean certain,
        boolean active,
        String note) {

    public RecurringRule {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(frequency, "frequency");
        Objects.requireNonNull(startDate, "startDate");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Le libellé est obligatoire");
        }
        label = label.strip();
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Le montant d'une récurrence doit être positif");
        }
        if (interval < 1) {
            throw new IllegalArgumentException("L'intervalle doit être supérieur ou égal à 1");
        }
        if (!frequency.isCustom()) {
            interval = 1;
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("La date de fin précède la date de début");
        }
        if (trackedFrom == null) {
            trackedFrom = startDate;
        }
        if (type == TransactionType.TRANSFER) {
            if (toAccountId == null || toAccountId == accountId) {
                throw new IllegalArgumentException("Un virement récurrent nécessite un compte destinataire différent");
            }
        } else if (toAccountId != null) {
            throw new IllegalArgumentException("Seul un virement a un compte destinataire");
        }
    }

    /** Montant signe tel qu'il s'applique au compte source. */
    public Money signedAmount() {
        return type == TransactionType.INCOME ? amount : amount.negate();
    }

    public RecurringRule withId(long newId) {
        return new RecurringRule(newId, accountId, toAccountId, type, label, amount, categoryId, frequency,
                interval, startDate, endDate, trackedFrom, certain, active, note);
    }

    public RecurringRule withTrackedFrom(LocalDate date) {
        return new RecurringRule(id, accountId, toAccountId, type, label, amount, categoryId, frequency,
                interval, startDate, endDate, date, certain, active, note);
    }

    public RecurringRule withEndDate(LocalDate newEndDate) {
        return new RecurringRule(id, accountId, toAccountId, type, label, amount, categoryId, frequency,
                interval, startDate, newEndDate, trackedFrom, certain, active, note);
    }
}

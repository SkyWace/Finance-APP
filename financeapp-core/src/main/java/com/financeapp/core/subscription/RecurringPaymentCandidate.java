package com.financeapp.core.subscription;

import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.Frequency;

import java.time.LocalDate;

/**
 * Paiement regulier repere dans l'historique mais pas encore enregistre comme
 * operation recurrente (abonnement probable).
 *
 * @param typicalAmount montant median (positif)
 * @param occurrences   nombre de paiements observes
 */
public record RecurringPaymentCandidate(
        String label,
        Money typicalAmount,
        Frequency frequency,
        int occurrences,
        LocalDate lastDate,
        LocalDate nextExpected,
        long accountId,
        Long categoryId) {

    public Money monthlyCost() {
        return typicalAmount.multiply(frequency.occurrencesPerYear(1)).divide(java.math.BigDecimal.valueOf(12));
    }
}

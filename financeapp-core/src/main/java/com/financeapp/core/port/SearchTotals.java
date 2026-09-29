package com.financeapp.core.port;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;

/**
 * Totaux d'une recherche, calcules sur TOUS les resultats (pas seulement la
 * page affichee), pour une devise. Operations annulees et virements internes exclus.
 *
 * @param count    nombre d'operations comptees
 * @param expenses total des depenses (negatif)
 * @param income   total des revenus (positif)
 */
public record SearchTotals(long count, long expenseCount, Money expenses, Money income) {

    /** Depense moyenne par operation de depense (positive), zero s'il n'y en a pas. */
    public Money averageExpense() {
        return expenseCount == 0 ? expenses.abs() : expenses.negate().divide(BigDecimal.valueOf(expenseCount));
    }
}

package com.financeapp.core.loan;

import com.financeapp.core.money.Money;

import java.time.LocalDate;

/**
 * Ligne du tableau d'amortissement.
 *
 * @param payment   mensualite hors assurance (= interets + capital)
 * @param remaining capital restant du apres cette mensualite
 */
public record AmortizationRow(int number, LocalDate date, Money payment, Money interest, Money principal,
                              Money insurance, Money remaining) {

    public Money totalPaid() {
        return payment.plus(insurance);
    }
}

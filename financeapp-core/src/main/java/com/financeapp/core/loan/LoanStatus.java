package com.financeapp.core.loan;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Situation d'un credit a une date, d'apres son tableau d'amortissement.
 *
 * @param percentRepaid part du capital deja remboursee, en % (une decimale)
 */
public record LoanStatus(Loan loan, AmortizationSchedule schedule, int paidCount, Money remaining,
                         Money repaid, BigDecimal percentRepaid, Optional<AmortizationRow> next) {

    public boolean finished() {
        return next.isEmpty();
    }

    public int remainingPayments() {
        return schedule.rows().size() - paidCount;
    }
}

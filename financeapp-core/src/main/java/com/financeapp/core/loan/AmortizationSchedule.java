package com.financeapp.core.loan;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Tableau d'amortissement complet.
 *
 * @param annualRate    taux nominal annuel (%) utilise pour le calcul
 * @param rateEstimated le taux n'etait pas connu : il a ete deduit de la mensualite
 * @param payment       mensualite courante hors assurance (la derniere peut differer de quelques centimes)
 */
public record AmortizationSchedule(List<AmortizationRow> rows, BigDecimal annualRate, boolean rateEstimated,
                                   Money payment, Money principal) {

    public AmortizationSchedule {
        rows = List.copyOf(rows);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Tableau vide");
        }
    }

    public Money totalInterest() {
        return rows.stream().map(AmortizationRow::interest).reduce(Money.zero(principal.currency()), Money::plus);
    }

    public Money totalInsurance() {
        return rows.stream().map(AmortizationRow::insurance).reduce(Money.zero(principal.currency()), Money::plus);
    }

    /** Cout du credit : interets + assurance. */
    public Money totalCost() {
        return totalInterest().plus(totalInsurance());
    }

    public LocalDate endDate() {
        return rows.getLast().date();
    }

    /** Mensualite assurance comprise. */
    public Money paymentWithInsurance() {
        return payment.plus(rows.getFirst().insurance());
    }

    /** Nombre de mensualites echues a cette date (incluse). */
    public int paidCount(LocalDate date) {
        int n = 0;
        for (AmortizationRow r : rows) {
            if (r.date().isAfter(date)) {
                break;
            }
            n++;
        }
        return n;
    }

    /** Capital restant du apres les mensualites echues a cette date. */
    public Money remainingAt(LocalDate date) {
        int paid = paidCount(date);
        return paid == 0 ? principal : rows.get(paid - 1).remaining();
    }

    /** Prochaine mensualite strictement apres cette date. */
    public Optional<AmortizationRow> nextAfter(LocalDate date) {
        return rows.stream().filter(r -> r.date().isAfter(date)).findFirst();
    }
}

package com.financeapp.core.loan;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Calculs de credit amortissable a mensualites constantes (methode dite
 * "francaise"), en {@link BigDecimal} uniquement.
 *
 * <ul>
 *   <li>Taux mensuel = taux nominal annuel / 12 (taux proportionnel, usage des
 *       offres de pret en France).</li>
 *   <li>Mensualite = C &middot; t / (1 - (1 + t)<sup>-n</sup>), arrondie au centime.</li>
 *   <li>Interets de chaque mois = capital restant &middot; t, arrondis au centime ; le
 *       capital rembourse est la difference. La derniere mensualite solde exactement
 *       le capital restant (ecart d'arrondi de quelques centimes).</li>
 *   <li>Taux inconnu : il est estime par dichotomie a partir de la mensualite.</li>
 * </ul>
 * L'assurance est un montant fixe par mois, hors amortissement.
 */
public final class LoanCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal TWELVE_HUNDRED = BigDecimal.valueOf(1200);
    private static final BigDecimal MAX_RATE = BigDecimal.valueOf(99);

    /** Mensualite hors assurance (arrondie au centime). */
    public Money payment(Money principal, BigDecimal annualRate, int months) {
        return Money.of(rawPayment(principal.amount(), annualRate, months), principal.currency());
    }

    /**
     * Taux nominal annuel (en %, trois decimales) qui donne cette mensualite.
     * Zero si la mensualite ne fait que rembourser le capital.
     */
    public BigDecimal impliedRate(Money principal, Money payment, int months) {
        BigDecimal total = payment.amount().multiply(BigDecimal.valueOf(months));
        if (total.compareTo(principal.amount()) < 0) {
            throw new IllegalArgumentException("Avec cette mensualité, le capital ne serait jamais remboursé en "
                    + months + " mois");
        }
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = MAX_RATE;
        if (rawPayment(principal.amount(), high, months).compareTo(payment.amount()) < 0) {
            throw new IllegalArgumentException("Mensualité incohérente avec le capital et la durée");
        }
        for (int i = 0; i < 80; i++) {
            BigDecimal mid = low.add(high).divide(BigDecimal.TWO, MC);
            if (rawPayment(principal.amount(), mid, months).compareTo(payment.amount()) < 0) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return low.add(high).divide(BigDecimal.TWO, MC).setScale(3, RoundingMode.HALF_EVEN);
    }

    public AmortizationSchedule schedule(Loan loan) {
        return schedule(loan.principal(), loan.annualRate(), loan.payment(), loan.termMonths(),
                loan.firstPaymentDate(), loan.monthlyInsurance());
    }

    /**
     * @param annualRate taux en % ou {@code null} (alors {@code payment} est obligatoire)
     * @param payment    mensualite hors assurance ou {@code null} (alors calculee)
     */
    public AmortizationSchedule schedule(Money principal, BigDecimal annualRate, Money payment, int months,
                                         LocalDate firstPaymentDate, Money insurance) {
        if (annualRate == null && payment == null) {
            throw new IllegalArgumentException("Indiquez le taux ou la mensualité");
        }
        boolean estimated = annualRate == null;
        BigDecimal rate = estimated ? impliedRate(principal, payment, months) : annualRate;
        Money monthly = payment != null ? payment : payment(principal, rate, months);
        Money fee = insurance == null ? Money.zero(principal.currency()) : insurance;
        BigDecimal monthlyRate = rate.divide(TWELVE_HUNDRED, MC);

        List<AmortizationRow> rows = new ArrayList<>();
        Money remaining = principal;
        for (int k = 0; k < months && remaining.isPositive(); k++) {
            Money interest = remaining.multiply(monthlyRate);
            Money capital = monthly.minus(interest);
            if (!capital.isPositive()) {
                throw new IllegalArgumentException("La mensualité ne couvre pas les intérêts : le capital ne diminuerait pas");
            }
            boolean last = k == months - 1 || capital.compareTo(remaining) >= 0;
            if (last) {
                capital = remaining;
            }
            remaining = remaining.minus(capital);
            rows.add(new AmortizationRow(k + 1, firstPaymentDate.plusMonths(k), capital.plus(interest), interest,
                    capital, fee, remaining));
        }
        return new AmortizationSchedule(rows, rate, estimated, monthly, principal);
    }

    /** Situation du credit a une date (mensualites echues incluses). */
    public LoanStatus status(Loan loan, LocalDate date) {
        AmortizationSchedule s = schedule(loan);
        int paid = s.paidCount(date);
        Money remaining = s.remainingAt(date);
        Money repaid = loan.principal().minus(remaining);
        BigDecimal percent = repaid.amount().multiply(BigDecimal.valueOf(100))
                .divide(loan.principal().amount(), 1, RoundingMode.HALF_EVEN);
        return new LoanStatus(loan, s, paid, remaining, repaid, percent, s.nextAfter(date));
    }

    private static BigDecimal rawPayment(BigDecimal principal, BigDecimal annualRate, int months) {
        if (months < 1) {
            throw new IllegalArgumentException("Durée invalide");
        }
        BigDecimal n = BigDecimal.valueOf(months);
        if (annualRate.signum() == 0) {
            return principal.divide(n, MC);
        }
        BigDecimal t = annualRate.divide(TWELVE_HUNDRED, MC);
        BigDecimal growth = BigDecimal.ONE.add(t).pow(months, MC);
        // C.t / (1 - (1+t)^-n) = C.t.(1+t)^n / ((1+t)^n - 1)
        return principal.multiply(t, MC).multiply(growth, MC).divide(growth.subtract(BigDecimal.ONE), MC);
    }
}

package com.financeapp.core.budget;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** Calculs purs des budgets : progression et part a reserver dans le disponible reel. */
public final class BudgetEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public BudgetProgress progress(Budget budget, String categoryName, Money spent, Money planned) {
        Money remaining = budget.limit().minus(spent);
        BigDecimal percent = spent.amount().multiply(HUNDRED)
                .divide(budget.limit().amount(), 1, RoundingMode.HALF_EVEN);
        BudgetStatus status;
        int cmp = spent.compareTo(budget.limit());
        if (cmp > 0) {
            status = BudgetStatus.EXCEEDED;
        } else if (cmp == 0) {
            status = BudgetStatus.REACHED;
        } else if (percent.compareTo(BigDecimal.valueOf(BudgetStatus.WARNING_PERCENT)) >= 0) {
            status = BudgetStatus.WARNING;
        } else {
            status = BudgetStatus.OK;
        }
        return new BudgetProgress(budget, categoryName, spent, planned, remaining, percent, status);
    }

    /**
     * Somme a reserver pour ce budget entre aujourd'hui et l'echeance (incluse).
     *
     * <ul>
     *   <li>Mois en cours : reste du budget, diminue des depenses deja prevues dans
     *       la categorie (deja deduites par ailleurs : pas de double comptage), au
     *       prorata des jours couverts parmi les jours restants du mois.</li>
     *   <li>Mois suivants : limite au prorata des jours couverts, diminuee des
     *       depenses prevues sur ces jours.</li>
     * </ul>
     *
     * @param spentThisMonth    depense du mois en cours (positif)
     * @param plannedByMonth    depenses prevues dans la categorie, par mois, limitees a la periode couverte (positif)
     */
    public Money reservation(Budget budget, Money spentThisMonth, Map<YearMonth, Money> plannedByMonth,
                             LocalDate today, LocalDate horizonEnd) {
        Money zero = Money.zero(budget.limit().currency());
        if (horizonEnd.isBefore(today)) {
            return zero;
        }
        Money total = zero;
        YearMonth month = YearMonth.from(today);
        YearMonth last = YearMonth.from(horizonEnd);
        while (!month.isAfter(last)) {
            LocalDate from = month.equals(YearMonth.from(today)) ? today : month.atDay(1);
            LocalDate to = month.equals(last) ? horizonEnd : month.atEndOfMonth();
            long covered = ChronoUnit.DAYS.between(from, to) + 1;
            Money planned = plannedByMonth.getOrDefault(month, zero);
            Money share;
            if (month.equals(YearMonth.from(today))) {
                long daysLeft = ChronoUnit.DAYS.between(today, month.atEndOfMonth()) + 1;
                Money left = budget.limit().minus(spentThisMonth).minus(planned);
                share = left.isPositive() ? prorate(left, covered, daysLeft) : zero;
            } else {
                Money part = prorate(budget.limit(), covered, month.lengthOfMonth()).minus(planned);
                share = part.isPositive() ? part : zero;
            }
            total = total.plus(share);
            month = month.plusMonths(1);
        }
        return total;
    }

    private static Money prorate(Money amount, long covered, long outOf) {
        if (covered >= outOf) {
            return amount;
        }
        return amount.multiply(BigDecimal.valueOf(covered)).divide(BigDecimal.valueOf(outOf));
    }
}

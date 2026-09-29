package com.financeapp.core.goal;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/**
 * Calculs purs d'un objectif d'epargne.
 *
 * <p>Exemple du cahier des charges : objectif 5 000 EUR, 3 250 EUR epargnes, echeance
 * decembre 2027, calcul en septembre 2026 → reste 1 750 EUR sur 15 mois
 * (octobre 2026 a decembre 2027) → 116,67 EUR par mois.
 */
public final class SavingsGoalCalculator {

    public GoalProgress progress(SavingsGoal goal, Money saved, LocalDate today) {
        Money zero = Money.zero(goal.target().currency());
        Money remaining = goal.target().minus(saved);
        boolean reached = !remaining.isPositive();
        if (reached) {
            remaining = zero;
        }
        BigDecimal percent = saved.amount().multiply(BigDecimal.valueOf(100))
                .divide(goal.target().amount(), 1, RoundingMode.HALF_EVEN)
                .min(BigDecimal.valueOf(100)).max(BigDecimal.ZERO);
        Integer monthsLeft = null;
        Money monthly = null;
        boolean overdue = false;
        if (goal.targetDate() != null) {
            // Mois pleins restants : du mois suivant jusqu'au mois de l'echeance inclus.
            long months = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(goal.targetDate()));
            monthsLeft = (int) Math.max(0, months);
            overdue = !reached && goal.targetDate().isBefore(today);
            if (!reached) {
                monthly = remaining.divide(BigDecimal.valueOf(Math.max(1, months)));
            }
        }
        return new GoalProgress(goal, saved, remaining, percent, monthsLeft, monthly, reached, overdue);
    }
}

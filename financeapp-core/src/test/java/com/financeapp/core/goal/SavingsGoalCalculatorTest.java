package com.financeapp.core.goal;

import com.financeapp.core.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SavingsGoalCalculatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private final SavingsGoalCalculator calculator = new SavingsGoalCalculator();

    private static SavingsGoal goal(String target, LocalDate date) {
        return new SavingsGoal(1L, "Fonds d'urgence", Money.eur(target), date, null, Money.eur("0"), false, false);
    }

    /** Exemple du brief : 5 000 EUR vises, 3 250 epargnes, decembre 2027. */
    @Test
    void briefExample() {
        GoalProgress p = calculator.progress(goal("5000", LocalDate.of(2027, 12, 31)), Money.eur("3250"), TODAY);
        assertEquals(Money.eur("1750"), p.remaining());
        assertEquals(new BigDecimal("65.0"), p.percent());
        assertEquals(15, p.monthsLeft());
        assertEquals(Money.eur("116.67"), p.monthlyNeeded());
        assertFalse(p.reached());
        assertFalse(p.overdue());
    }

    @Test
    void reachedGoal() {
        GoalProgress p = calculator.progress(goal("1000", LocalDate.of(2027, 1, 1)), Money.eur("1200"), TODAY);
        assertTrue(p.reached());
        assertTrue(p.remaining().isZero());
        assertEquals(new BigDecimal("100"), p.percent().stripTrailingZeros().setScale(0));
        assertNull(p.monthlyNeeded());
    }

    @Test
    void withoutDeadlineThereIsNoMonthlyEffort() {
        GoalProgress p = calculator.progress(goal("1000", null), Money.eur("100"), TODAY);
        assertNull(p.monthsLeft());
        assertNull(p.monthlyNeeded());
    }

    @Test
    void pastDeadlineIsFlaggedAndTheWholeRemainderIsDueNow() {
        GoalProgress p = calculator.progress(goal("1000", LocalDate.of(2026, 6, 30)), Money.eur("400"), TODAY);
        assertTrue(p.overdue());
        assertEquals(0, p.monthsLeft());
        assertEquals(Money.eur("600"), p.monthlyNeeded());
    }

    @Test
    void deadlineThisMonthMeansOneMonthOfEffort() {
        GoalProgress p = calculator.progress(goal("1000", LocalDate.of(2026, 9, 30)), Money.eur("900"), TODAY);
        assertEquals(Money.eur("100"), p.monthlyNeeded());
    }
}

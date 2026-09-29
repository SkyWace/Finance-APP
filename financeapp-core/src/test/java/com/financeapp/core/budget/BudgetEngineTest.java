package com.financeapp.core.budget;

import com.financeapp.core.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BudgetEngineTest {

    private final BudgetEngine engine = new BudgetEngine();

    private static Budget budget(String limit) {
        return new Budget(1L, 10, Money.eur(limit), true, true);
    }

    /** Exemple du brief : carburant 184 / 250 EUR, 66 EUR restants, 73,6 %. */
    @Test
    void progressOfTheBriefExample() {
        BudgetProgress p = engine.progress(budget("250"), "Carburant", Money.eur("184"), Money.eur("0"));
        assertEquals(Money.eur("66"), p.remaining());
        assertEquals(new BigDecimal("73.6"), p.percent());
        assertEquals(BudgetStatus.OK, p.status());
    }

    @Test
    void statuses() {
        assertEquals(BudgetStatus.WARNING, engine.progress(budget("250"), "x", Money.eur("200"), Money.eur("0")).status());
        assertEquals(BudgetStatus.REACHED, engine.progress(budget("250"), "x", Money.eur("250"), Money.eur("0")).status());
        BudgetProgress over = engine.progress(budget("250"), "x", Money.eur("262.50"), Money.eur("0"));
        assertEquals(BudgetStatus.EXCEEDED, over.status());
        assertEquals(Money.eur("-12.50"), over.remaining());
        assertEquals(new BigDecimal("105.0"), over.percent());
    }

    @Test
    void reservesTheRestOfTheMonthUntilMonthEnd() {
        LocalDate today = LocalDate.of(2026, 9, 29);
        assertEquals(Money.eur("50"), engine.reservation(budget("300"), Money.eur("250"), Map.of(), today,
                LocalDate.of(2026, 9, 30)));
    }

    @Test
    void shortHorizonReservesAProrataOfTheRemainingDays() {
        // 1er octobre, 31 jours restants, echeance au 4 : 4/31 du budget restant
        LocalDate today = LocalDate.of(2026, 10, 1);
        assertEquals(Money.eur("40"), engine.reservation(budget("310"), Money.eur("0"), Map.of(), today,
                LocalDate.of(2026, 10, 4)));
    }

    @Test
    void horizonOverTwoMonthsAddsAProrataOfNextMonth() {
        // Reste de septembre (50) + 27/31 du budget d'octobre (261,29)
        LocalDate today = LocalDate.of(2026, 9, 29);
        assertEquals(Money.eur("311.29"), engine.reservation(budget("300"), Money.eur("250"), Map.of(), today,
                LocalDate.of(2026, 10, 27)));
    }

    @Test
    void plannedExpensesOfTheCategoryAreNotCountedTwice() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        Map<YearMonth, Money> planned = Map.of(YearMonth.of(2026, 10), Money.eur("80"));
        assertEquals(Money.eur("20"), engine.reservation(budget("300"), Money.eur("200"), planned, today,
                LocalDate.of(2026, 10, 31)));
    }

    @Test
    void exceededBudgetReservesNothing() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        assertTrue(engine.reservation(budget("300"), Money.eur("320"), Map.of(), today,
                LocalDate.of(2026, 10, 31)).isZero());
    }
}

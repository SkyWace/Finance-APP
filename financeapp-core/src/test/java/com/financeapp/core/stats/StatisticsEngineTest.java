package com.financeapp.core.stats;

import com.financeapp.core.money.Money;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StatisticsEngineTest {

    private static final long FOOD = 1;
    private static final long GROCERIES = 11;
    private static final long TRANSPORT = 2;
    private static final Map<Long, Long> ROOTS = Map.of(FOOD, FOOD, GROCERIES, FOOD, TRANSPORT, TRANSPORT);
    private static final Map<Long, String> NAMES = Map.of(FOOD, "Alimentation", TRANSPORT, "Transport");

    private final StatisticsEngine engine = new StatisticsEngine();

    private static Transaction tx(String date, String amount, TransactionType type, Long category) {
        Money m = Money.eur(amount);
        return new Transaction(1L, 1, LocalDate.parse(date), "t", m, type, TransactionStatus.COMPLETED, category, null,
                type == TransactionType.TRANSFER ? "g" : null, type == TransactionType.TRANSFER ? 2L : null, null, null);
    }

    /** Indicateur de trajectoire du brief : 1 850 de revenus, 1 242 de depenses → 608 epargnes, 32,9 %. */
    @Test
    void monthlySummaryAndSavingsRate() {
        List<Transaction> txs = List.of(
                tx("2026-09-28", "1850", TransactionType.INCOME, null),
                tx("2026-09-03", "-650", TransactionType.EXPENSE, null),
                tx("2026-09-10", "-592", TransactionType.EXPENSE, GROCERIES),
                tx("2026-09-01", "-100", TransactionType.TRANSFER, null),
                tx("2026-08-10", "-500", TransactionType.EXPENSE, FOOD));

        List<MonthSummary> months = engine.monthly(txs, YearMonth.of(2026, 7), YearMonth.of(2026, 9), Money.EUR);

        assertEquals(3, months.size());
        MonthSummary sept = months.get(2);
        assertEquals(Money.eur("1850"), sept.income());
        assertEquals(Money.eur("-1242"), sept.expenses());
        assertEquals(Money.eur("608"), sept.saved());
        assertEquals(new BigDecimal("32.9"), sept.savingsRate());
        assertNull(months.getFirst().savingsRate(), "aucun revenu en juillet");
        assertEquals(Money.eur("580.67"), engine.averageExpenses(months, Money.EUR));
    }

    @Test
    void subcategoriesAreGroupedUnderTheirRoot() {
        List<Transaction> txs = List.of(
                tx("2026-09-03", "-60", TransactionType.EXPENSE, GROCERIES),
                tx("2026-09-04", "-15", TransactionType.EXPENSE, FOOD),
                tx("2026-09-05", "-25", TransactionType.EXPENSE, TRANSPORT),
                tx("2026-09-06", "-10", TransactionType.EXPENSE, null),
                tx("2026-09-07", "40", TransactionType.INCOME, GROCERIES));

        List<CategoryAmount> byCat = engine.byCategory(txs, Money.EUR, ROOTS::get, NAMES);

        assertEquals("Alimentation", byCat.getFirst().name());
        assertEquals(Money.eur("75"), byCat.getFirst().amount());
        assertEquals(new BigDecimal("68.2"), byCat.getFirst().share());
        assertEquals("Sans catégorie", byCat.getLast().name());
    }

    @Test
    void comparisonShowsAbsoluteAmountsWithPercentages() {
        List<Transaction> august = List.of(
                tx("2026-08-03", "-200", TransactionType.EXPENSE, GROCERIES),
                tx("2026-08-05", "-100", TransactionType.EXPENSE, TRANSPORT));
        List<Transaction> september = List.of(
                tx("2026-09-03", "-228", TransactionType.EXPENSE, GROCERIES),
                tx("2026-09-05", "-92", TransactionType.EXPENSE, TRANSPORT),
                tx("2026-09-06", "-30", TransactionType.EXPENSE, null));

        List<CategoryComparison> cmp = engine.compare(august, september, Money.EUR, ROOTS::get, NAMES);

        CategoryComparison food = cmp.stream().filter(c -> "Alimentation".equals(c.name())).findFirst().orElseThrow();
        assertEquals(Money.eur("28"), food.delta());
        assertEquals(new BigDecimal("14.0"), food.percentChange());
        CategoryComparison transport = cmp.stream().filter(c -> "Transport".equals(c.name())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("-8.0"), transport.percentChange());
        CategoryComparison none = cmp.stream().filter(c -> c.categoryId() == null).findFirst().orElseThrow();
        assertNull(none.percentChange(), "pas de pourcentage depuis zero");
        assertEquals(Money.eur("30"), none.delta());

        CategoryComparison total = engine.total(august, september, Money.EUR);
        assertEquals(Money.eur("300"), total.reference());
        assertEquals(Money.eur("350"), total.current());
        assertEquals(new BigDecimal("16.7"), total.percentChange());
    }
}

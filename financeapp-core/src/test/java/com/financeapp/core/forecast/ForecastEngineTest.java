package com.financeapp.core.forecast;

import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ForecastEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private final ForecastEngine engine = new ForecastEngine();

    private static PlannedItem planned(LocalDate date, String amount, TransactionType type) {
        return new PlannedItem(date, 1, "p", Money.eur(amount), type, null,
                PlannedItem.Source.RECURRING, null, 1L, null, true);
    }

    private static Transaction realized(LocalDate date, String amount) {
        Money m = Money.eur(amount);
        return new Transaction(1L, 1, date, "r", m, m.isNegative() ? TransactionType.EXPENSE : TransactionType.INCOME,
                TransactionStatus.COMPLETED, null, null, null, null, null, null);
    }

    /** Exemple du brief : 1420 -> 1289 (5) -> 1095 (10) -> 742 (20) -> 2592 (28) -> 2411 (31). */
    @Test
    void projectsBalanceDayByDay() {
        List<PlannedItem> items = List.of(
                planned(TODAY.withDayOfMonth(5), "-131", TransactionType.EXPENSE),
                planned(TODAY.withDayOfMonth(10), "-194", TransactionType.EXPENSE),
                planned(TODAY.withDayOfMonth(20), "-353", TransactionType.EXPENSE),
                planned(TODAY.withDayOfMonth(28), "1850", TransactionType.INCOME),
                planned(TODAY.withDayOfMonth(31), "-181", TransactionType.EXPENSE));

        Forecast f = engine.compute(new ForecastInput(Set.of(1L), Money.eur("1420"), TODAY, TODAY, List.of(),
                TODAY.withDayOfMonth(31), items));

        assertEquals(31, f.projection().size());
        assertEquals(Money.eur("1420"), balanceOn(f, 1));
        assertEquals(Money.eur("1289"), balanceOn(f, 5));
        assertEquals(Money.eur("1095"), balanceOn(f, 10));
        assertEquals(Money.eur("742"), balanceOn(f, 20));
        assertEquals(Money.eur("2592"), balanceOn(f, 28));
        assertEquals(Money.eur("2411"), f.endBalance());
        assertEquals(TODAY.withDayOfMonth(20), f.lowestProjected().date());
        assertTrue(f.firstNegative().isEmpty());
        assertTrue(f.projection().stream().allMatch(ForecastPoint::projected));
    }

    @Test
    void reconstructsRealHistoryBackwards() {
        List<Transaction> history = List.of(
                realized(TODAY.minusDays(2), "-50"),
                realized(TODAY.minusDays(1), "200"),
                realized(TODAY, "-30"));

        Forecast f = engine.compute(new ForecastInput(Set.of(1L), Money.eur("1000"), TODAY, TODAY.minusDays(3),
                history, TODAY, List.of()));

        List<ForecastPoint> h = f.history();
        assertEquals(4, h.size());
        assertEquals(Money.eur("880"), h.get(0).balance());  // J-3
        assertEquals(Money.eur("830"), h.get(1).balance());  // J-2 : -50
        assertEquals(Money.eur("1030"), h.get(2).balance()); // J-1 : +200
        assertEquals(Money.eur("1000"), h.get(3).balance()); // aujourd'hui : -30
        assertFalse(h.getFirst().projected());
    }

    @Test
    void overdueItemsApplyTodayAndNegativeBalanceIsDetected() {
        List<PlannedItem> items = List.of(
                planned(TODAY.minusDays(3), "-80", TransactionType.EXPENSE),
                planned(TODAY.plusDays(2), "-50", TransactionType.EXPENSE));

        Forecast f = engine.compute(new ForecastInput(Set.of(1L), Money.eur("100"), TODAY, TODAY, List.of(),
                TODAY.plusDays(5), items));

        assertEquals(Money.eur("20"), f.projection().getFirst().balance());
        assertEquals(TODAY.plusDays(2), f.firstNegative().orElseThrow().date());
        assertEquals(Money.eur("-30"), f.lowestProjected().balance());
    }

    @Test
    void internalTransfersWithinScopeAreNeutral() {
        PlannedItem out = new PlannedItem(TODAY.plusDays(1), 1, "v", Money.eur("-100"), TransactionType.TRANSFER,
                null, PlannedItem.Source.RECURRING, null, 1L, 2L, true);
        PlannedItem in = new PlannedItem(TODAY.plusDays(1), 2, "v", Money.eur("100"), TransactionType.TRANSFER,
                null, PlannedItem.Source.RECURRING, null, 1L, 1L, true);
        PlannedItem toSavings = new PlannedItem(TODAY.plusDays(1), 1, "épargne", Money.eur("-40"), TransactionType.TRANSFER,
                null, PlannedItem.Source.RECURRING, null, 2L, 9L, true);

        Forecast f = engine.compute(new ForecastInput(Set.of(1L, 2L), Money.eur("500"), TODAY, TODAY, List.of(),
                TODAY.plusDays(2), List.of(out, in, toSavings)));

        assertEquals(Money.eur("460"), f.endBalance());
    }

    private static Money balanceOn(Forecast f, int dayOfMonth) {
        return f.projection().stream().filter(p -> p.date().getDayOfMonth() == dayOfMonth)
                .findFirst().orElseThrow().balance();
    }
}

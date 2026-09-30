package com.financeapp.core.forecast;

import com.financeapp.core.money.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class VariableSpendingForecastTest {

    private final ForecastEngine engine = new ForecastEngine();

    @Test
    void aFullMonthReceivesExactlyTheMonthlyEstimate() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        Forecast f = engine.compute(new ForecastInput(Set.of(1L), Money.eur("1000"), today, today, List.of(),
                LocalDate.of(2026, 10, 31), List.of(), Money.eur("300")));
        assertEquals(Money.eur("1000"), f.projection().getFirst().balance(), "rien n'est deduit aujourd'hui");
        assertEquals(Money.eur("700"), f.endBalance(), "31 jours : 30 x 9,68 + 9,60");
        assertEquals(Money.eur("990.32"), f.projection().get(1).balance());
    }

    @Test
    void dailyShareSumsToTheMonthlyAmount() {
        Money monthly = Money.eur("451.37");
        for (LocalDate first : List.of(LocalDate.of(2026, 2, 1), LocalDate.of(2028, 2, 1), LocalDate.of(2026, 4, 1))) {
            Money total = Money.eur("0");
            for (LocalDate d = first; d.getMonth() == first.getMonth(); d = d.plusDays(1)) {
                total = total.plus(ForecastEngine.variableShare(monthly, d));
            }
            assertEquals(monthly, total, first.toString());
        }
    }

    @Test
    void withoutEstimateTheProjectionIsUnchanged() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        Forecast f = engine.compute(new ForecastInput(Set.of(1L), Money.eur("1000"), today, today, List.of(),
                LocalDate.of(2027, 9, 30), List.of()));
        assertEquals(Money.eur("1000"), f.endBalance());
    }
}

package com.financeapp.core.service;

import com.financeapp.core.available.HorizonType;
import com.financeapp.core.money.Money;
import com.financeapp.core.testing.TestApp;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Premiere ouverture apres installation : aucun compte, aucune operation. Rien ne doit echouer. */
class FirstRunTest {

    private final TestApp app = new TestApp(LocalDate.of(2026, 9, 30));

    @Test
    void everyScreenCanBeComputedWithoutAnyData() {
        assertEquals(Money.eur("0"), app.available.computeDefault().available());
        for (HorizonType type : HorizonType.values()) {
            assertDoesNotThrow(() -> app.available.compute(type, LocalDate.of(2026, 10, 15)), type.name());
        }
        assertDoesNotThrow(() -> app.dashboard.summary());
        assertDoesNotThrow(() -> app.forecast.forecast(90, 1461, true));
        assertDoesNotThrow(() -> app.statistics.report(java.time.YearMonth.of(2026, 9), java.time.YearMonth.of(2026, 8), 12));
        assertDoesNotThrow(() -> app.budgets.progress(java.time.YearMonth.of(2026, 9)));
        assertDoesNotThrow(() -> app.goals.progress());
        assertDoesNotThrow(() -> app.subscriptions.overview());
        assertDoesNotThrow(() -> app.subscriptions.detectCandidates());
        assertDoesNotThrow(() -> app.loans.statuses());
        assertTrue(app.inbox.items().isEmpty());
        assertFalse(app.bankSync.isEnabled());
    }
}

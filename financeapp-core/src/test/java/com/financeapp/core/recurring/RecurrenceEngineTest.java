package com.financeapp.core.recurring;

import com.financeapp.core.money.Money;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static java.time.LocalDate.of;
import static org.junit.jupiter.api.Assertions.*;

class RecurrenceEngineTest {

    private final RecurrenceEngine engine = new RecurrenceEngine();

    private static RecurringRule rule(Frequency f, int interval, LocalDate start, LocalDate end) {
        return new RecurringRule(1L, 1, null, TransactionType.EXPENSE, "Test", Money.eur("10"), null,
                f, interval, start, end, null, true, true, null);
    }

    @Test
    void monthlyOnThe31stClampsWithoutDrifting() {
        List<LocalDate> dates = engine.occurrences(rule(Frequency.MONTHLY, 1, of(2026, 1, 31), null),
                of(2026, 1, 1), of(2026, 5, 31));
        assertEquals(List.of(of(2026, 1, 31), of(2026, 2, 28), of(2026, 3, 31), of(2026, 4, 30), of(2026, 5, 31)), dates);
    }

    @Test
    void salaryOnThe28thInWindow() {
        List<LocalDate> dates = engine.occurrences(rule(Frequency.MONTHLY, 1, of(2025, 1, 28), null),
                of(2026, 10, 1), of(2026, 12, 31));
        assertEquals(List.of(of(2026, 10, 28), of(2026, 11, 28), of(2026, 12, 28)), dates);
    }

    @Test
    void windowBoundsAreInclusive() {
        RecurringRule r = rule(Frequency.MONTHLY, 1, of(2026, 1, 5), null);
        assertEquals(List.of(of(2026, 10, 5)), engine.occurrences(r, of(2026, 10, 5), of(2026, 10, 5)));
    }

    @Test
    void respectsStartAndEndDates() {
        RecurringRule r = rule(Frequency.MONTHLY, 1, of(2026, 3, 10), of(2026, 5, 10));
        assertEquals(List.of(of(2026, 3, 10), of(2026, 4, 10), of(2026, 5, 10)),
                engine.occurrences(r, of(2026, 1, 1), of(2026, 12, 31)));
    }

    @Test
    void weeklyAndBiweeklyFromAnOldStartDate() {
        RecurringRule weekly = rule(Frequency.WEEKLY, 1, of(2020, 1, 6), null); // un lundi
        assertEquals(List.of(of(2026, 9, 28), of(2026, 10, 5)),
                engine.occurrences(weekly, of(2026, 9, 28), of(2026, 10, 11)));

        RecurringRule biweekly = rule(Frequency.BIWEEKLY, 1, of(2026, 9, 1), null);
        assertEquals(List.of(of(2026, 9, 1), of(2026, 9, 15), of(2026, 9, 29)),
                engine.occurrences(biweekly, of(2026, 9, 1), of(2026, 10, 12)));
    }

    @Test
    void quarterlyYearlyAndLeapDay() {
        assertEquals(List.of(of(2026, 1, 15), of(2026, 4, 15), of(2026, 7, 15), of(2026, 10, 15)),
                engine.occurrences(rule(Frequency.QUARTERLY, 1, of(2026, 1, 15), null), of(2026, 1, 1), of(2026, 12, 31)));
        assertEquals(List.of(of(2028, 2, 29), of(2029, 2, 28), of(2030, 2, 28)),
                engine.occurrences(rule(Frequency.YEARLY, 1, of(2028, 2, 29), null), of(2028, 1, 1), of(2030, 12, 31)));
    }

    @Test
    void customIntervals() {
        assertEquals(List.of(of(2026, 10, 1), of(2026, 10, 11), of(2026, 10, 21), of(2026, 10, 31)),
                engine.occurrences(rule(Frequency.EVERY_N_DAYS, 10, of(2026, 10, 1), null), of(2026, 10, 1), of(2026, 10, 31)));
        assertEquals(List.of(of(2026, 2, 28), of(2026, 8, 28)),
                engine.occurrences(rule(Frequency.EVERY_N_MONTHS, 6, of(2025, 8, 28), null), of(2026, 1, 1), of(2026, 12, 31)));
    }

    @Test
    void inactiveRuleHasNoOccurrence() {
        RecurringRule active = rule(Frequency.MONTHLY, 1, of(2026, 1, 1), null);
        RecurringRule inactive = new RecurringRule(1L, 1, null, TransactionType.EXPENSE, "x", Money.eur("1"), null,
                Frequency.MONTHLY, 1, of(2026, 1, 1), null, null, true, false, null);
        assertFalse(engine.occurrences(active, of(2026, 1, 1), of(2026, 12, 31)).isEmpty());
        assertTrue(engine.occurrences(inactive, of(2026, 1, 1), of(2026, 12, 31)).isEmpty());
    }

    @Test
    void intervalIsIgnoredForStandardFrequencies() {
        RecurringRule r = rule(Frequency.MONTHLY, 5, of(2026, 1, 1), null);
        assertEquals(1, r.interval());
    }

    @Test
    void nextOccurrence() {
        RecurringRule r = rule(Frequency.MONTHLY, 1, of(2026, 1, 10), null);
        assertEquals(of(2026, 10, 10), engine.nextOccurrence(r, of(2026, 9, 30)).orElseThrow());
    }

    @Test
    void monthlyEquivalents() {
        // Montant de la regle de test : 10 EUR
        assertEquals(Money.eur("-0.83"), rule(Frequency.YEARLY, 1, of(2026, 1, 1), null).monthlyEquivalent());
        assertEquals(Money.eur("-10.00"), rule(Frequency.MONTHLY, 1, of(2026, 1, 1), null).monthlyEquivalent());
        assertEquals(Money.eur("-3.33"), rule(Frequency.QUARTERLY, 1, of(2026, 1, 1), null).monthlyEquivalent());
        assertEquals(Money.eur("-5.00"), rule(Frequency.EVERY_N_MONTHS, 2, of(2026, 1, 1), null).monthlyEquivalent());
        // 10 EUR par semaine = 10 x 365,25 / 7 / 12 = 43,48 EUR par mois
        assertEquals(Money.eur("-43.48"), rule(Frequency.WEEKLY, 1, of(2026, 1, 1), null).monthlyEquivalent());
    }
}

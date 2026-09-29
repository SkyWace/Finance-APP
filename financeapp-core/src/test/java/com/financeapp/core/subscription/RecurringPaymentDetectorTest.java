package com.financeapp.core.subscription;

import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecurringPaymentDetectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);
    private final RecurringPaymentDetector detector = new RecurringPaymentDetector();

    private static Transaction expense(LocalDate date, String label, String amount) {
        return new Transaction(1L, 1, date, label, Money.eur(amount).negate(), TransactionType.EXPENSE,
                TransactionStatus.COMPLETED, null, null, null, null, null, null);
    }

    @Test
    void detectsAMonthlySubscriptionWithVaryingLabelsAndDates() {
        List<Transaction> history = List.of(
                expense(LocalDate.of(2026, 6, 5), "PRLV SEPA NETFLIX.COM 0605", "21.99"),
                expense(LocalDate.of(2026, 7, 6), "PRLV SEPA NETFLIX.COM 0706", "21.99"),
                expense(LocalDate.of(2026, 8, 5), "PRLV SEPA NETFLIX.COM 0805", "21.99"),
                expense(LocalDate.of(2026, 9, 4), "PRLV SEPA NETFLIX.COM 0904", "22.99"));

        List<RecurringPaymentCandidate> found = detector.detect(history, List.of(), TODAY);

        assertEquals(1, found.size());
        RecurringPaymentCandidate c = found.getFirst();
        assertEquals(Frequency.MONTHLY, c.frequency());
        assertEquals(4, c.occurrences());
        assertEquals(Money.eur("21.99"), c.typicalAmount());
        assertEquals(LocalDate.of(2026, 10, 4), c.nextExpected());
    }

    @Test
    void variableShoppingIsNotASubscription() {
        List<Transaction> history = new ArrayList<>();
        String[] amounts = {"45.30", "82.10", "61.00", "39.90", "74.20", "58.00"};
        for (int i = 0; i < amounts.length; i++) {
            history.add(expense(LocalDate.of(2026, 8, 1).plusWeeks(i), "Carrefour Market", amounts[i]));
        }
        assertTrue(detector.detect(history, List.of(), TODAY).isEmpty());
    }

    @Test
    void alreadyRegisteredOrStoppedPaymentsAreIgnored() {
        List<Transaction> gym = List.of(
                expense(LocalDate.of(2026, 7, 15), "Salle de sport", "30"),
                expense(LocalDate.of(2026, 8, 15), "Salle de sport", "30"),
                expense(LocalDate.of(2026, 9, 15), "Salle de sport", "30"));
        RecurringRule rule = new RecurringRule(1L, 1, null, TransactionType.EXPENSE, "SALLE DE SPORT", Money.eur("30"),
                null, Frequency.MONTHLY, 1, LocalDate.of(2026, 7, 15), null, null, true, true, null);
        assertTrue(detector.detect(gym, List.of(rule), TODAY).isEmpty());

        List<Transaction> stopped = List.of(
                expense(LocalDate.of(2026, 3, 2), "Musique Premium", "10.99"),
                expense(LocalDate.of(2026, 4, 2), "Musique Premium", "10.99"),
                expense(LocalDate.of(2026, 5, 2), "Musique Premium", "10.99"));
        assertTrue(detector.detect(stopped, List.of(), TODAY).isEmpty(), "plusieurs echeances manquees");
    }

    @Test
    void yearlyPaymentNeedsTwoOccurrences() {
        List<Transaction> history = List.of(
                expense(LocalDate.of(2025, 11, 10), "Assurance habitation", "180"),
                expense(LocalDate.of(2026, 11, 10).minusYears(0).minusDays(365), "x", "1"));
        List<Transaction> yearly = List.of(
                expense(LocalDate.of(2024, 11, 10), "Assurance habitation", "175"),
                expense(LocalDate.of(2025, 11, 10), "Assurance habitation", "180"));
        assertTrue(detector.detect(history, List.of(), TODAY).isEmpty());
        RecurringPaymentCandidate c = detector.detect(yearly, List.of(), TODAY).getFirst();
        assertEquals(Frequency.YEARLY, c.frequency());
        assertEquals(Money.eur("15.00"), c.monthlyCost());
    }

    @Test
    void normalization() {
        assertEquals("netflix com", RecurringPaymentDetector.normalize("PRLV SEPA NETFLIX.COM 12/09"));
        assertEquals("electricite edf", RecurringPaymentDetector.normalize("Électricité EDF"));
    }
}

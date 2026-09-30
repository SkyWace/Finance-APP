package com.financeapp.core.loan;

import com.financeapp.core.money.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class LoanCalculatorTest {

    private final LoanCalculator calc = new LoanCalculator();

    private static Loan loan(String principal, String rate, int months, String payment, LocalDate first) {
        return new Loan(null, "Prêt", Money.eur(principal), rate == null ? null : new BigDecimal(rate), months, first,
                payment == null ? null : Money.eur(payment), null, null, null, null, false, null);
    }

    @Test
    void constantPaymentMatchesReferenceValues() {
        assertEquals(Money.eur("856.07"), calc.payment(Money.eur("10000"), new BigDecimal("5"), 12));
        assertEquals(Money.eur("1109.20"), calc.payment(Money.eur("200000"), new BigDecimal("3"), 240));
        assertEquals(Money.eur("250.84"), calc.payment(Money.eur("11000"), new BigDecimal("4.5"), 48));
    }

    @Test
    void scheduleRepaysTheCapitalExactly() {
        AmortizationSchedule s = calc.schedule(loan("10000", "5", 12, null, LocalDate.of(2026, 1, 15)));
        assertEquals(12, s.rows().size());
        assertEquals(Money.eur("272.89"), s.totalInterest());
        assertEquals(Money.eur("856.12"), s.rows().getLast().payment(), "la derniere mensualite absorbe l'arrondi");
        assertTrue(s.rows().getLast().remaining().isZero());
        Money capital = s.rows().stream().map(AmortizationRow::principal).reduce(Money.eur("0"), Money::plus);
        assertEquals(Money.eur("10000"), capital);
        for (AmortizationRow r : s.rows()) {
            assertEquals(r.payment(), r.interest().plus(r.principal()));
        }
        assertEquals(Money.eur("41.67"), s.rows().getFirst().interest(), "10 000 x 5 % / 12");
        assertEquals(LocalDate.of(2026, 12, 15), s.endDate());
    }

    @Test
    void longMortgageTotalInterest() {
        AmortizationSchedule s = calc.schedule(loan("200000", "3", 240, null, LocalDate.of(2026, 1, 5)));
        assertEquals(Money.eur("66206.43"), s.totalInterest());
        assertEquals(240, s.rows().size());
    }

    @Test
    void zeroRateSplitsTheCapital() {
        AmortizationSchedule s = calc.schedule(loan("1200", "0", 12, null, LocalDate.of(2026, 1, 1)));
        assertEquals(Money.eur("100"), s.payment());
        assertTrue(s.totalInterest().isZero());
    }

    @Test
    void unknownRateIsEstimatedFromThePayment() {
        AmortizationSchedule s = calc.schedule(loan("11000", null, 48, "250", LocalDate.of(2026, 11, 10)));
        assertTrue(s.rateEstimated());
        assertEquals(new BigDecimal("4.331"), s.annualRate());
        assertEquals(48, s.rows().size());
        assertTrue(s.rows().getLast().remaining().isZero());
        assertTrue(s.rows().getLast().payment().minus(Money.eur("250")).abs().compareTo(Money.eur("1")) < 0,
                "l'estimation ne laisse qu'un ecart de centimes");
    }

    @Test
    void endOfMonthDatesDoNotDrift() {
        AmortizationSchedule s = calc.schedule(loan("3000", "2", 3, null, LocalDate.of(2026, 1, 31)));
        assertEquals(LocalDate.of(2026, 2, 28), s.rows().get(1).date());
        assertEquals(LocalDate.of(2026, 3, 31), s.rows().get(2).date());
    }

    @Test
    void statusReflectsPaymentsDueAtTheDate() {
        Loan l = loan("10000", "5", 12, null, LocalDate.of(2026, 1, 15));
        LoanStatus st = calc.status(l, LocalDate.of(2026, 3, 20));
        AmortizationSchedule s = st.schedule();
        assertEquals(3, st.paidCount());
        assertEquals(s.rows().get(2).remaining(), st.remaining());
        assertEquals(LocalDate.of(2026, 4, 15), st.next().orElseThrow().date());
        assertEquals(Money.eur("10000"), st.remaining().plus(st.repaid()));
        assertEquals(9, st.remainingPayments());

        LoanStatus before = calc.status(l, LocalDate.of(2026, 1, 14));
        assertEquals(Money.eur("10000"), before.remaining());
        assertEquals(0, before.percentRepaid().signum());
        assertTrue(calc.status(l, LocalDate.of(2027, 1, 1)).finished());
        assertEquals(new BigDecimal("100.0"), calc.status(l, LocalDate.of(2027, 1, 1)).percentRepaid());
    }

    @Test
    void inconsistentInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> calc.schedule(loan("10000", null, 12, "100", LocalDate.of(2026, 1, 1))),
                "12 x 100 < 10 000");
        assertThrows(IllegalArgumentException.class,
                () -> calc.schedule(loan("100000", "50", 12, "10", LocalDate.of(2026, 1, 1))),
                "la mensualite ne couvre pas les interets");
        assertThrows(IllegalArgumentException.class, () -> loan("10000", null, 12, null, LocalDate.of(2026, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> loan("10000", "5", 0, null, LocalDate.of(2026, 1, 1)));
    }
}

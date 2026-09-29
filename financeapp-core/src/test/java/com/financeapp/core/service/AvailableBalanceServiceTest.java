package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.available.AvailableBalanceResult;
import com.financeapp.core.available.AvailableBalanceResult.SectionKind;
import com.financeapp.core.available.Horizon;
import com.financeapp.core.available.HorizonType;
import com.financeapp.core.forecast.Forecast;
import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** Scenarios bout a bout : services + moteurs + stockage memoire. */
class AvailableBalanceServiceTest {

    /** Un mardi. */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    private final TestApp app = new TestApp(TODAY);
    private final Account checking = app.account("Compte courant", AccountType.CHECKING, "1420");
    private final Account livret = app.account("Livret A", AccountType.PASSBOOK, "4200");

    private void typicalMonth() {
        app.monthly(checking, TransactionType.INCOME, "Salaire", "1850", LocalDate.of(2026, 1, 28));
        app.monthly(checking, TransactionType.EXPENSE, "Loyer", "650", LocalDate.of(2026, 1, 3));
        app.monthly(checking, TransactionType.EXPENSE, "Assurance", "101", LocalDate.of(2026, 1, 5));
        app.monthly(checking, TransactionType.EXPENSE, "Crédit auto", "194", LocalDate.of(2026, 1, 10));
        app.monthly(checking, TransactionType.EXPENSE, "Salle de sport", "30", LocalDate.of(2026, 1, 15));
        app.recurring.save(new RecurringRule(null, checking.id(), livret.id(), TransactionType.TRANSFER, "Épargne",
                Money.eur("100"), null, Frequency.MONTHLY, 1, LocalDate.of(2026, 1, 1), null, null, true, true, null));
    }

    @Test
    void horizons() {
        typicalMonth();
        assertEquals(LocalDate.of(2026, 10, 4), app.available.resolve(HorizonType.END_OF_WEEK, null).end());
        assertEquals(LocalDate.of(2026, 9, 30), app.available.resolve(HorizonType.END_OF_MONTH, null).end());
        Horizon payday = app.available.resolve(HorizonType.NEXT_PAYDAY, null);
        assertEquals(LocalDate.of(2026, 10, 28), payday.payday());
        assertEquals(LocalDate.of(2026, 10, 27), payday.end());
        assertEquals(TODAY, app.available.resolve(HorizonType.CUSTOM_DATE, TODAY.minusDays(3)).end());
    }

    @Test
    void untilNextPaydayAllChargesAreDeductedButNotTheSalaryItself() {
        typicalMonth();

        AvailableBalanceResult r = app.available.compute(HorizonType.NEXT_PAYDAY, null);

        // 1420 - (650 + 101 + 194 + 30) - 100 d'epargne = 345
        assertEquals(Money.eur("-975"), r.total(SectionKind.PLANNED_EXPENSES));
        assertEquals(Money.eur("-100"), r.total(SectionKind.SAVINGS_TRANSFERS));
        assertTrue(r.section(SectionKind.EXPECTED_INCOME).isEmpty());
        assertEquals(Money.eur("345"), r.available());
        // Le livret (hors perimetre) n'apparait pas dans le solde de depart
        assertEquals(1, r.section(SectionKind.CURRENT_BALANCE).orElseThrow().lines().size());
    }

    @Test
    void customDateIncludingThePaydayCountsTheSalary() {
        typicalMonth();

        AvailableBalanceResult r = app.available.compute(HorizonType.CUSTOM_DATE, LocalDate.of(2026, 10, 31));

        assertEquals(Money.eur("2195"), r.available()); // 345 + 1850
        app.settings.setIncludeCertainIncome(false);
        assertEquals(Money.eur("345"), app.available.compute(HorizonType.CUSTOM_DATE, LocalDate.of(2026, 10, 31)).available());
    }

    @Test
    void plannedOneOffOperationsAreDeductedAndPendingOnesAreAlreadyInTheBalance() {
        app.expense(checking, LocalDate.of(2026, 9, 30), "Garagiste", "250", TransactionStatus.PLANNED);
        app.expense(checking, TODAY, "Carte en attente", "20", TransactionStatus.PENDING);

        AvailableBalanceResult r = app.available.compute(HorizonType.END_OF_MONTH, null);

        assertEquals(Money.eur("1400"), r.total(SectionKind.CURRENT_BALANCE));
        assertEquals(Money.eur("1150"), r.available());
    }

    @Test
    void forecastMatchesTheRecurringCalendar() {
        typicalMonth();

        Forecast f = app.forecast.forecast(7, 30);

        // 01/10 : -100 (epargne) ; 03/10 : -650 ; 05/10 : -101 ; 10/10 : -194 ; 15/10 : -30 ; 28/10 : +1850
        assertEquals(Money.eur("1420"), f.projection().getFirst().balance());
        assertEquals(Money.eur("345"), f.lowestProjected().balance());
        assertEquals(Money.eur("2195"), f.endBalance());
        assertEquals(8, f.history().size());
    }
}

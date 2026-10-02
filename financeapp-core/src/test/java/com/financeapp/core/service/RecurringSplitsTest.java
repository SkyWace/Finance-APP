package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.budget.Budget;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.transaction.SplitLine;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ventilation des operations recurrentes. Aujourd'hui : 10/10/2026. */
class RecurringSplitsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private final TestApp app = new TestApp(TODAY);
    private Account checking;
    private Category rent;
    private Category charges;
    private Category streaming;
    private RecurringRule housing;

    @BeforeEach
    void setUp() {
        checking = app.account("Compte courant", AccountType.CHECKING, "3000");
        Category home = app.categories.create(null, "Logement", CategoryKind.EXPENSE);
        rent = app.categories.create(home.id(), "Loyer", null);
        charges = app.categories.create(home.id(), "Charges", null);
        Category subs = app.store.categories.save(new Category(null, null, "Abonnements", CategoryKind.EXPENSE,
                null, null, false, 0, SubscriptionService.SUBSCRIPTIONS_CODE));
        streaming = app.categories.create(subs.id(), "Streaming", null);
        // Loyer 800 EUR le 15 : 700 de loyer + 100 de charges.
        housing = app.recurring.save(new RecurringRule(null, checking.id(), null, TransactionType.EXPENSE, "Loyer",
                Money.eur("800"), null, Frequency.MONTHLY, 1, LocalDate.of(2026, 1, 15), null, null, true, true, null,
                List.of(new SplitLine(rent.id(), Money.eur("700")), new SplitLine(charges.id(), Money.eur("100")))));
    }

    @Test
    void aRuleSplitMustMatchItsAmount() {
        assertThrows(IllegalArgumentException.class, () -> new RecurringRule(null, checking.id(), null,
                TransactionType.EXPENSE, "X", Money.eur("100"), null, Frequency.MONTHLY, 1, TODAY, null, null, true,
                true, null, List.of(new SplitLine(rent.id(), Money.eur("60")), new SplitLine(charges.id(), Money.eur("30")))));
        assertTrue(housing.isSplit());
        assertNull(housing.categoryId());
        assertEquals(rent.id(), housing.mainCategoryId());
    }

    @Test
    void upcomingOccurrencesCarryTheSplit() {
        PlannedItem next = app.planning.upcoming(LocalDate.of(2026, 10, 31)).stream()
                .filter(i -> i.recurringId() != null).findFirst().orElseThrow();
        assertEquals(Money.eur("-800"), next.amount(), "un seul mouvement sur le compte");
        assertEquals(List.of(Money.eur("-700"), Money.eur("-100")), next.splits().stream().map(SplitLine::amount).toList());
    }

    @Test
    void confirmingAtThePlannedAmountKeepsTheSplitAndOtherwiseAdjustsIt() {
        Transaction same = app.recurring.confirm(housing.id(), LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 15),
                new BigDecimal("800")).getFirst();
        assertEquals(List.of(Money.eur("-700"), Money.eur("-100")), same.splits().stream().map(SplitLine::amount).toList());

        Transaction more = app.recurring.confirm(housing.id(), LocalDate.of(2026, 2, 15), LocalDate.of(2026, 2, 16),
                new BigDecimal("820")).getFirst();
        assertEquals(Money.eur("-820"), more.amount());
        assertEquals(List.of(Money.eur("-717.50"), Money.eur("-102.50")), more.splits().stream().map(SplitLine::amount).toList(),
                "repartie au prorata");

        Transaction odd = app.recurring.confirm(housing.id(), LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 15),
                new BigDecimal("100.01")).getFirst();
        assertEquals(Money.eur("-100.01"), odd.splits().stream().map(SplitLine::amount).reduce(Money.eur("0"), Money::plus),
                "l'arrondi est absorbe par la derniere ligne");
    }

    @Test
    void budgetsReserveOnlyEachShareOfAPlannedOccurrence() {
        app.budgets.save(new Budget(null, charges.id(), Money.eur("150"), false, true));
        var progress = app.budgets.progress(YearMonth.of(2026, 10)).getFirst();
        assertEquals(Money.eur("100"), progress.planned(), "seulement les charges du loyer du 15/10");
    }

    @Test
    void subscriptionsCountOnlyTheSubscriptionShare() {
        // Box internet 50 EUR : 30 d'abonnement TV + 20 de telephone (hors abonnements).
        app.recurring.save(new RecurringRule(null, checking.id(), null, TransactionType.EXPENSE, "Box",
                Money.eur("50"), null, Frequency.MONTHLY, 1, LocalDate.of(2026, 1, 3), null, null, true, true, null,
                List.of(new SplitLine(streaming.id(), Money.eur("30")), new SplitLine(charges.id(), Money.eur("20")))));
        var overview = app.subscriptions.overview();
        assertEquals(1, overview.subscriptions().size(), "le loyer n'est pas un abonnement");
        assertEquals(Money.eur("30"), overview.monthlyTotal());
    }
}

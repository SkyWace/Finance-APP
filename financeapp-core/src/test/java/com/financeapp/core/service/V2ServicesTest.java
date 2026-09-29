package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.available.AvailableBalanceResult;
import com.financeapp.core.available.AvailableBalanceResult.SectionKind;
import com.financeapp.core.available.HorizonType;
import com.financeapp.core.budget.Budget;
import com.financeapp.core.budget.BudgetProgress;
import com.financeapp.core.budget.BudgetStatus;
import com.financeapp.core.calendar.CalendarDay;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.goal.GoalProgress;
import com.financeapp.core.goal.SavingsGoal;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.SearchTotals;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.testing.TestApp;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Budgets, objectifs, abonnements, calendrier et recherche : scenarios bout a bout. */
class V2ServicesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private final TestApp app = new TestApp(TODAY);
    private Account checking;
    private Account livret;
    private Category food;
    private Category groceries;
    private Category streaming;

    @BeforeEach
    void setUp() {
        checking = app.account("Compte courant", AccountType.CHECKING, "1000");
        livret = app.account("Livret A", AccountType.PASSBOOK, "3250");
        food = app.categories.create(null, "Alimentation", CategoryKind.EXPENSE);
        groceries = app.categories.create(food.id(), "Courses", null);
        Category subs = app.store.categories.save(new Category(null, null, "Abonnements", CategoryKind.EXPENSE,
                null, null, false, 0, SubscriptionService.SUBSCRIPTIONS_CODE));
        streaming = app.categories.create(subs.id(), "Streaming", null);
    }

    /** Scenario du cahier des charges avec un vrai budget : 1000 + 1800 - 700 - 200 - 300 = 1600. */
    @Test
    void budgetIsReservedInTheRealAvailableBalance() {
        app.monthly(checking, TransactionType.INCOME, "Salaire", "1800", LocalDate.of(2026, 10, 28));
        app.monthly(checking, TransactionType.EXPENSE, "Loyer", "700", LocalDate.of(2026, 10, 5));
        app.monthly(checking, TransactionType.EXPENSE, "Crédit", "200", LocalDate.of(2026, 10, 10));
        app.budgets.save(new Budget(null, food.id(), Money.eur("300"), true, true));

        AvailableBalanceResult r = app.available.compute(HorizonType.END_OF_MONTH, null);

        assertEquals(Money.eur("-300"), r.total(SectionKind.RESERVATIONS));
        assertEquals(Money.eur("1600"), r.available());
        assertEquals(Money.eur("1900"), r.availableBeforeReservations(), "si aucune autre dépense variable");
    }

    @Test
    void budgetProgressCountsSubcategoriesButNotIncomeNorTransfers() {
        Budget b = app.budgets.save(new Budget(null, food.id(), Money.eur("250"), true, true));
        app.expense(checking, TODAY, "Carrefour", "150", groceries.id());
        app.expense(checking, TODAY, "Boulangerie", "34", food.id());
        app.income(checking, TODAY, "Remboursement", "20", TransactionStatus.COMPLETED);
        app.expense(checking, TODAY.plusDays(3), "Drive prévu", "40", TransactionStatus.PLANNED);
        app.transactions.create(new TransactionDraft(checking.id(), TODAY.plusDays(5), "Marché prévu",
                new BigDecimal("16"), TransactionType.EXPENSE, TransactionStatus.PLANNED, groceries.id(), null));

        BudgetProgress p = app.budgets.progress(YearMonth.from(TODAY)).getFirst();

        assertEquals(b.id(), p.budget().id());
        assertEquals(Money.eur("184"), p.spent());
        assertEquals(Money.eur("16"), p.planned());
        assertEquals(BudgetStatus.OK, p.status());
        // Reserve : 250 - 184 - 16 deja prevus = 50 (les 16 sont deduits comme depense prevue)
        AvailableBalanceResult r = app.available.compute(HorizonType.END_OF_MONTH, null);
        assertEquals(Money.eur("-50"), r.total(SectionKind.RESERVATIONS));
    }

    @Test
    void budgetRules() {
        app.budgets.save(new Budget(null, food.id(), Money.eur("300"), true, true));
        assertThrows(BusinessException.class, () -> app.budgets.save(new Budget(null, food.id(), Money.eur("100"), true, true)));
        Category salary = app.categories.create(null, "Revenus", CategoryKind.INCOME);
        assertThrows(BusinessException.class, () -> app.budgets.save(new Budget(null, salary.id(), Money.eur("100"), true, true)));
    }

    @Test
    void savingsGoalLinkedToAnAccountAndReservation() {
        SavingsGoal g = app.goals.save(new SavingsGoal(null, "Fonds d'urgence", Money.eur("5000"),
                LocalDate.of(2027, 12, 31), livret.id(), Money.eur("0"), true, false));

        GoalProgress p = app.goals.progress().getFirst();
        assertEquals(Money.eur("3250"), p.saved());
        // Octobre 2026 -> decembre 2027 : 14 mois apres le mois en cours
        assertEquals(14, p.monthsLeft());
        assertEquals(Money.eur("125"), p.monthlyNeeded());

        AvailableBalanceResult r = app.available.compute(HorizonType.END_OF_MONTH, null);
        assertEquals(Money.eur("-125"), r.total(SectionKind.RESERVATIONS));
        assertThrows(BusinessException.class, () -> app.goals.addContribution(g.id(), BigDecimal.TEN));
    }

    @Test
    void manualGoalContributions() {
        SavingsGoal g = app.goals.save(new SavingsGoal(null, "Vacances", Money.eur("1200"), null, null,
                Money.eur("0"), false, false));
        app.goals.addContribution(g.id(), new BigDecimal("300"));
        app.goals.addContribution(g.id(), new BigDecimal("-50"));
        assertEquals(Money.eur("250"), app.goals.get(g.id()).manualSaved());
        assertThrows(BusinessException.class, () -> app.goals.addContribution(g.id(), new BigDecimal("-500")));
        assertTrue(app.available.compute(HorizonType.END_OF_MONTH, null).section(SectionKind.RESERVATIONS).isEmpty(),
                "non reserve par defaut");
    }

    @Test
    void subscriptionsOverviewAndDetection() {
        app.recurring.save(new RecurringRule(null, checking.id(), null, TransactionType.EXPENSE, "Netflix",
                Money.eur("22"), streaming.id(), Frequency.MONTHLY, 1, LocalDate.of(2026, 10, 5), null, null, true, true, null));
        app.recurring.save(new RecurringRule(null, checking.id(), null, TransactionType.EXPENSE, "Presse",
                Money.eur("120"), streaming.id(), Frequency.YEARLY, 1, LocalDate.of(2027, 1, 5), null, null, true, true, null));
        app.monthly(checking, TransactionType.EXPENSE, "Loyer", "700", LocalDate.of(2026, 10, 5));
        for (int m = 7; m <= 9; m++) {
            app.expense(checking, LocalDate.of(2026, m, 12), "Spotify", "10.99", (Long) null);
        }

        SubscriptionService.Overview o = app.subscriptions.overview();
        assertEquals(2, o.subscriptions().size(), "le loyer n'est pas un abonnement");
        assertEquals(Money.eur("32"), o.monthlyTotal());
        assertEquals(Money.eur("384"), o.yearlyTotal());

        var candidates = app.subscriptions.detectCandidates();
        assertEquals(1, candidates.size());
        assertEquals("Spotify", candidates.getFirst().label());
        app.subscriptions.dismiss(candidates.getFirst());
        assertTrue(app.subscriptions.detectCandidates().isEmpty(), "un paiement ignore n'est plus propose");
    }

    @Test
    void calendarShowsOperationsAndProjectedBalanceDayByDay() {
        app.monthly(checking, TransactionType.EXPENSE, "Loyer", "700", LocalDate.of(2026, 10, 5));
        app.monthly(checking, TransactionType.INCOME, "Salaire", "1800", LocalDate.of(2026, 10, 28));

        List<CalendarDay> days = app.calendar.month(YearMonth.of(2026, 10));

        assertEquals(31, days.size());
        assertEquals("Loyer", days.get(4).planned().getFirst().label());
        assertEquals(Money.eur("1000"), days.get(3).balance());
        assertEquals(Money.eur("300"), days.get(4).balance());
        assertEquals(Money.eur("2100"), days.get(30).balance());
        assertTrue(days.get(30).projected());
    }

    @Test
    void advancedSearchWithTotals() {
        app.expense(checking, TODAY.minusDays(3), "CARREFOUR MARKET", "46.30", groceries.id());
        app.expense(checking, TODAY.minusDays(10), "Carrefour Drive", "90.00", groceries.id());
        app.expense(checking, TODAY.minusDays(2), "Carrefour", "12.00", (Long) null);
        app.expense(checking, TODAY.minusDays(1), "Total", "60.00", (Long) null);

        TransactionQuery q = new TransactionQuery(null, null, null, "carrefour", food.id(), null,
                TransactionType.EXPENSE, new BigDecimal("40"), null, 50, 0);
        assertEquals(2, app.transactions.search(q).size());
        SearchTotals totals = app.transactions.summarize(q, Money.EUR);
        assertEquals(2, totals.count());
        assertEquals(Money.eur("-136.30"), totals.expenses());
        assertEquals(Money.eur("68.15"), totals.averageExpense());
    }
}

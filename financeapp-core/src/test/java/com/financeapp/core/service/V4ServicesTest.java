package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.goal.SavingsGoal;
import com.financeapp.core.loan.Loan;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.simulation.MonthComparison;
import com.financeapp.core.simulation.Simulation;
import com.financeapp.core.simulation.SimulationItem;
import com.financeapp.core.simulation.SimulationResult;
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

/** Credits, previsions longues et simulations sur une situation realiste (aujourd'hui : 30/09/2026). */
class V4ServicesTest {

    private final TestApp app = new TestApp(LocalDate.of(2026, 9, 30));
    private Account checking;
    private Account livret;
    private RecurringRule rent;

    @BeforeEach
    void setUp() {
        checking = app.account("Compte courant", AccountType.CHECKING, "2000");
        livret = app.account("Livret A", AccountType.PASSBOOK, "3000");
        app.monthly(checking, TransactionType.INCOME, "Salaire", "1850", LocalDate.of(2026, 7, 28));
        rent = app.monthly(checking, TransactionType.EXPENSE, "Loyer", "650", LocalDate.of(2026, 7, 3));
        app.recurring.save(new RecurringRule(null, checking.id(), livret.id(), TransactionType.TRANSFER,
                "Épargne mensuelle", Money.eur("100"), null, Frequency.MONTHLY, 1, LocalDate.of(2026, 7, 1), null,
                null, true, true, null));
        // Depenses courantes des trois derniers mois complets (juin, juillet, aout) : 400 EUR/mois en moyenne.
        app.expense(checking, LocalDate.of(2026, 6, 10), "Courses", "380", TransactionStatus.COMPLETED);
        app.expense(checking, LocalDate.of(2026, 7, 10), "Courses", "420", TransactionStatus.COMPLETED);
        app.expense(checking, LocalDate.of(2026, 8, 10), "Courses", "400", TransactionStatus.COMPLETED);
    }

    private Loan carLoan(String insurance) {
        return new Loan(null, "Crédit auto", Money.eur("11000"), new BigDecimal("4.5"), 48, LocalDate.of(2026, 11, 10),
                null, Money.eur(insurance), checking.id(), null, null, false, null);
    }

    // ------------------------------------------------------------------ credits

    @Test
    void loanCreatesItsMonthlyPaymentAsARecurringExpense() {
        Loan loan = app.loans.save(carLoan("10"), true);
        assertNotNull(loan.recurringId());
        RecurringRule rule = app.recurring.get(loan.recurringId());
        assertEquals(Money.eur("260.84"), rule.amount(), "250,84 + 10 d'assurance");
        assertEquals(LocalDate.of(2030, 10, 10), rule.endDate(), "48e mensualite");

        List<PlannedItem> upcoming = app.planning.upcoming(LocalDate.of(2026, 12, 31)).stream()
                .filter(p -> loan.recurringId().equals(p.recurringId())).toList();
        assertEquals(List.of(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 12, 10)),
                upcoming.stream().map(PlannedItem::date).toList());

        app.loans.save(new Loan(loan.id(), loan.name(), loan.principal(), loan.annualRate(), 48, loan.firstPaymentDate(),
                null, Money.eur("12"), checking.id(), loan.recurringId(), null, false, null), true);
        assertEquals(Money.eur("262.84"), app.recurring.get(loan.recurringId()).amount(), "l'echeance liee suit le credit");
        assertEquals(1, app.recurring.findAll().stream().filter(r -> r.label().equals("Crédit auto")).count());

        assertFalse(app.loans.linkableRules(null).stream().anyMatch(r -> r.id().equals(loan.recurringId())));
        assertTrue(app.loans.linkableRules(loan.id()).stream().anyMatch(r -> r.id().equals(loan.recurringId())));
        assertEquals(Money.eur("11000"), app.loans.totalRemaining(Money.EUR), "aucune mensualite echue");
    }

    @Test
    void inconsistentPaymentIsRejectedWithTheComputedValue() {
        Loan loan = new Loan(null, "Prêt", Money.eur("11000"), new BigDecimal("4.5"), 48, LocalDate.of(2026, 11, 10),
                Money.eur("100"), null, checking.id(), null, null, false, null);
        BusinessException e = assertThrows(BusinessException.class, () -> app.loans.save(loan, false));
        assertTrue(e.getMessage().contains("250,84"), e.getMessage());
    }

    // -------------------------------------------------------------- previsions

    @Test
    void variableSpendingIsEstimatedFromRecentNonRecurringExpenses() {
        // Loyers passes saisis a la main ou importes sans rapprochement : deja projetes par la recurrence.
        app.expense(checking, LocalDate.of(2026, 7, 3), "PRLV SEPA LOYER JUILLET", "650", TransactionStatus.COMPLETED);
        app.expense(checking, LocalDate.of(2026, 8, 3), "Loyer", "655", TransactionStatus.COMPLETED);
        // Meme libelle mais montant tres different : une vraie depense courante.
        app.expense(checking, LocalDate.of(2026, 8, 20), "Loyer garage", "90", TransactionStatus.COMPLETED);
        assertEquals(Money.eur("430"), app.forecast.variableMonthlyEstimate(), "(380 + 420 + 400 + 90) / 3");
        var without = app.forecast.forecast(0, 365 * 2, false);
        var with = app.forecast.forecast(0, 365 * 2, true);
        assertTrue(with.endBalance().compareTo(without.endBalance()) < 0);
        assertEquals(LocalDate.of(2028, 9, 29), with.projection().getLast().date());
    }

    // -------------------------------------------------------------- simulations

    private Simulation carPurchase() {
        return new Simulation(null, "Achat voiture", 12, List.of(
                new SimulationItem(null, SimulationItem.Kind.ONE_TIME, "Apport", Money.eur("-4000"),
                        LocalDate.of(2026, 10, 15), null, null, null, null),
                new SimulationItem(null, SimulationItem.Kind.LOAN, "Crédit voiture", Money.eur("11000"),
                        LocalDate.of(2026, 11, 10), 48, new BigDecimal("4.5"), null, null),
                new SimulationItem(null, SimulationItem.Kind.MONTHLY, "Assurance auto", Money.eur("-100"),
                        LocalDate.of(2026, 11, 1), null, null, null, null),
                new SimulationItem(null, SimulationItem.Kind.MONTHLY, "Carburant", Money.eur("-200"),
                        LocalDate.of(2026, 11, 1), null, null, null, null),
                new SimulationItem(null, SimulationItem.Kind.MONTHLY, "Entretien", Money.eur("-50"),
                        LocalDate.of(2026, 11, 1), null, null, null, null)));
    }

    @Test
    void carPurchaseScenarioShowsTheImpactBeforeAndAfter() {
        SimulationResult r = app.simulations.run(carPurchase());

        assertEquals(YearMonth.of(2026, 10), r.firstMonth());
        assertEquals(YearMonth.of(2027, 9), r.lastMonth());
        assertEquals(Money.eur("1850"), r.before().income());
        assertEquals(Money.eur("650"), r.before().fixedCharges());
        assertEquals(Money.eur("400"), r.before().variableSpending());
        assertEquals(Money.eur("100"), r.before().scheduledSavings(), "virement vers le livret");
        assertEquals(Money.eur("1200"), r.before().livingRemainder());
        assertEquals(Money.eur("700"), r.before().available());
        assertEquals(Money.eur("800"), r.before().savingCapacity());

        // 11 mensualites de 250,84 + 11 x 350 de frais, sur 12 mois ; l'apport de 4 000 lisse sur 12 mois.
        assertEquals(Money.eur("1200.77"), r.after().fixedCharges());
        assertEquals(Money.eur("333.33"), r.after().oneOffExpenses());
        assertEquals(Money.eur("-184.10"), r.after().available());
        assertEquals(Money.eur("-884.10"), r.availableImpact());

        MonthComparison december = r.months().stream().filter(m -> m.month().equals(YearMonth.of(2026, 12)))
                .findFirst().orElseThrow();
        assertEquals(Money.eur("700"), december.before());
        assertEquals(Money.eur("99.16"), december.after(), "mois type : 700 - 250,84 - 350");

        assertEquals(1, r.loans().size());
        assertEquals(Money.eur("250.84"), r.loans().getFirst().payment());
        assertEquals(Money.eur("1040.24"), r.loans().getFirst().totalInterest());
        assertEquals(r.baselineForecast().endBalance().minus(Money.eur("10609.24")), r.scenarioForecast().endBalance(),
                "4 000 + 11 x 250,84 + 11 x 350");
    }

    @Test
    void stoppingARecurringExpenseRemovesItsFutureOccurrences() {
        SimulationResult r = app.simulations.run(new Simulation(null, "Déménager chez des amis", 12, List.of(
                new SimulationItem(null, SimulationItem.Kind.STOP_RECURRING, "Loyer", null,
                        LocalDate.of(2027, 1, 1), null, null, null, rent.id()))));
        assertEquals(9, r.removed().size(), "janvier a septembre 2027");
        assertEquals(Money.eur("162.50"), r.after().fixedCharges(), "3 loyers sur 12 mois");
    }

    @Test
    void savingsGoalsAreComparedToTheSavingCapacity() {
        app.goals.save(new SavingsGoal(null, "Fonds d'urgence", Money.eur("5000"), LocalDate.of(2027, 12, 31), null,
                Money.eur("3250"), false, false));
        SimulationResult r = app.simulations.run(carPurchase());
        assertEquals(1, r.goals().size());
        assertTrue(r.goalsMonthlyNeeded().isPositive());
        assertTrue(r.after().savingCapacity().compareTo(r.goalsMonthlyNeeded()) < 0,
                "apres l'achat, l'effort d'epargne n'est plus couvert");
        assertTrue(r.before().savingCapacity().compareTo(r.goalsMonthlyNeeded()) >= 0);
    }

    @Test
    void aSimulationNeverTouchesRealData() {
        app.loans.save(carLoan("0"), true);
        int transactions = app.store.allTransactions().size();
        List<RecurringRule> rules = app.recurring.findAll();
        Money balance = app.accounts.balanceOf(checking.id());
        var availableBefore = app.available.computeDefault().available();

        Simulation saved = app.simulations.save(carPurchase());
        app.simulations.run(saved);
        app.simulations.run(new Simulation(null, "Arrêt", 24, List.of(new SimulationItem(null,
                SimulationItem.Kind.STOP_RECURRING, "Loyer", null, LocalDate.of(2026, 10, 1), null, null, null, rent.id()))));

        assertEquals(transactions, app.store.allTransactions().size());
        assertEquals(rules, app.recurring.findAll());
        assertEquals(balance, app.accounts.balanceOf(checking.id()));
        assertEquals(availableBefore, app.available.computeDefault().available());
        assertEquals(5, app.simulations.get(saved.id()).items().size());
    }

    @Test
    void simulationInputsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new SimulationItem(null, SimulationItem.Kind.LOAN, "Prêt",
                Money.eur("1000"), LocalDate.of(2026, 11, 1), null, BigDecimal.ONE, null, null), "duree obligatoire");
        assertThrows(IllegalArgumentException.class, () -> new SimulationItem(null, SimulationItem.Kind.MONTHLY, "X",
                Money.eur("0"), LocalDate.of(2026, 11, 1), null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Simulation(null, " ", 12, List.of()));
        assertThrows(BusinessException.class, () -> app.simulations.run(new Simulation(null, "Prêt impossible", 12,
                List.of(new SimulationItem(null, SimulationItem.Kind.LOAN, "Prêt", Money.eur("10000"),
                        LocalDate.of(2026, 11, 1), 12, null, Money.eur("100"), null)))));
    }
}

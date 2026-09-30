package com.financeapp.core.testing;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurrenceEngine;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.service.AccountService;
import com.financeapp.core.service.AvailableBalanceService;
import com.financeapp.core.service.BudgetService;
import com.financeapp.core.service.CategorizationService;
import com.financeapp.core.service.ImportService;
import com.financeapp.core.service.InboxService;
import com.financeapp.core.service.LoanService;
import com.financeapp.core.service.SimulationService;
import com.financeapp.core.service.CalendarService;
import com.financeapp.core.service.SavingsGoalService;
import com.financeapp.core.service.StatisticsService;
import com.financeapp.core.service.SubscriptionService;
import com.financeapp.core.service.CategoryService;
import com.financeapp.core.service.DashboardService;
import com.financeapp.core.service.ForecastService;
import com.financeapp.core.service.PlanningService;
import com.financeapp.core.service.RecurringService;
import com.financeapp.core.service.TransactionDraft;
import com.financeapp.core.service.TransactionService;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Assemble tous les services sur un stockage en memoire et une date figee. */
public final class TestApp {

    public final InMemoryStore store = new InMemoryStore();
    public final LocalDate today;
    public final SettingsService settings;
    public final AccountService accounts;
    public final CategoryService categories;
    public final TransactionService transactions;
    public final RecurringService recurring;
    public final PlanningService planning;
    public final AvailableBalanceService available;
    public final ForecastService forecast;
    public final DashboardService dashboard;
    public final BudgetService budgets;
    public final SavingsGoalService goals;
    public final SubscriptionService subscriptions;
    public final StatisticsService statistics;
    public final CalendarService calendar;
    public final CategorizationService categorization;
    public final InboxService inbox;
    public final ImportService imports;
    public final LoanService loans;
    public final SimulationService simulations;

    public TestApp(LocalDate today) {
        this.today = today;
        Clock clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        settings = new SettingsService(store.settings);
        accounts = new AccountService(store.accounts, store.transactions, store.rules);
        categories = new CategoryService(store.categories);
        transactions = new TransactionService(store.accounts, store.transactions);
        recurring = new RecurringService(store.rules, store.transactions, store.accounts, transactions,
                new RecurrenceEngine(), clock);
        planning = new PlanningService(store.transactions, recurring, clock);
        budgets = new BudgetService(store.budgets, store.transactions, planning, categories, settings);
        goals = new SavingsGoalService(store.goals, accounts, planning);
        available = new AvailableBalanceService(accounts, planning, recurring, settings, java.util.List.of(budgets, goals));
        subscriptions = new SubscriptionService(recurring, categories, store.transactions, planning, settings);
        statistics = new StatisticsService(store.transactions, categories, accounts, planning, settings);
        forecast = new ForecastService(available, planning, store.transactions, settings, recurring);
        dashboard = new DashboardService(accounts, store.transactions, planning, available, settings);
        calendar = new CalendarService(store.transactions, planning, forecast);
        categorization = new CategorizationService(store.categorizationRules, store.transactions, categories, planning);
        inbox = new InboxService(store.transactions, categorization);
        imports = new ImportService(store.imports, store.transactions, accounts, planning, categorization);
        loans = new LoanService(store.loans, recurring, accounts, clock);
        simulations = new SimulationService(store.simulations, forecast, goals, planning);
    }

    public Account account(String name, AccountType type, String initialBalance) {
        return accounts.save(Account.create(name, type, Money.eur(initialBalance), today.minusYears(1)));
    }

    public Transaction expense(Account a, LocalDate date, String label, String amount, TransactionStatus status) {
        return transactions.create(new TransactionDraft(a.id(), date, label, new BigDecimal(amount),
                TransactionType.EXPENSE, status, null, null));
    }

    public Transaction expense(Account a, LocalDate date, String label, String amount, Long categoryId) {
        return transactions.create(new TransactionDraft(a.id(), date, label, new BigDecimal(amount),
                TransactionType.EXPENSE, TransactionStatus.COMPLETED, categoryId, null));
    }

    public Transaction income(Account a, LocalDate date, String label, String amount, TransactionStatus status) {
        return transactions.create(new TransactionDraft(a.id(), date, label, new BigDecimal(amount),
                TransactionType.INCOME, status, null, null));
    }

    public RecurringRule monthly(Account a, TransactionType type, String label, String amount, LocalDate start) {
        return recurring.save(new RecurringRule(null, a.id(), null, type, label, Money.eur(amount), null,
                Frequency.MONTHLY, 1, start, null, null, true, true, null));
    }
}

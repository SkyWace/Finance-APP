package com.financeapp.core.testing;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurrenceEngine;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.service.AccountService;
import com.financeapp.core.service.AvailableBalanceService;
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
        available = new AvailableBalanceService(accounts, planning, recurring, settings);
        forecast = new ForecastService(available, planning, store.transactions, settings);
        dashboard = new DashboardService(accounts, store.transactions, planning, available, settings);
    }

    public Account account(String name, AccountType type, String initialBalance) {
        return accounts.save(Account.create(name, type, Money.eur(initialBalance), today.minusYears(1)));
    }

    public Transaction expense(Account a, LocalDate date, String label, String amount, TransactionStatus status) {
        return transactions.create(new TransactionDraft(a.id(), date, label, new BigDecimal(amount),
                TransactionType.EXPENSE, status, null, null));
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

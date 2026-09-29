package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.available.AccountBalance;
import com.financeapp.core.dashboard.DashboardSummary;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Agrege les indicateurs affiches sur le tableau de bord. */
public final class DashboardService {

    private final AccountService accounts;
    private final TransactionRepository transactions;
    private final PlanningService planning;
    private final AvailableBalanceService available;
    private final SettingsService settings;

    public DashboardService(AccountService accounts, TransactionRepository transactions, PlanningService planning,
                            AvailableBalanceService available, SettingsService settings) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.planning = planning;
        this.available = available;
        this.settings = settings;
    }

    public DashboardSummary summary() {
        Currency currency = settings.baseCurrency();
        Money zero = Money.zero(currency);
        LocalDate today = planning.today();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.with(TemporalAdjusters.lastDayOfMonth());

        List<AccountBalance> all = accounts.balancesOf(currency, a -> true);
        Set<Long> baseAccounts = all.stream().map(AccountBalance::accountId).collect(Collectors.toSet());
        Map<Long, Account> byId = accounts.findAll().stream().collect(Collectors.toMap(Account::id, a -> a));

        Money netWorth = sum(all, zero);
        Money current = sum(accounts.balancesOf(currency, group(AccountType.Group.CURRENT)), zero);
        Money savings = sum(accounts.balancesOf(currency, group(AccountType.Group.SAVINGS)), zero);

        Money income = zero;
        Money expenses = zero;
        for (Transaction t : transactions.findCounted(monthStart, monthEnd)) {
            if (!baseAccounts.contains(t.accountId())) {
                continue;
            }
            if (t.type() == TransactionType.INCOME) {
                income = income.plus(t.amount());
            } else if (t.type() == TransactionType.EXPENSE) {
                expenses = expenses.plus(t.amount());
            }
        }

        List<PlannedItem> upcoming = planning.upcomingForDisplay(monthEnd).stream()
                .filter(i -> baseAccounts.contains(i.accountId()))
                .toList();
        Money upcomingOut = upcoming.stream()
                .filter(i -> i.type() == TransactionType.EXPENSE)
                .map(PlannedItem::amount)
                .reduce(zero, Money::plus);

        List<PlannedItem> next = planning.upcomingForDisplay(today.plusDays(30)).stream()
                .filter(i -> baseAccounts.contains(i.accountId()))
                .limit(8)
                .toList();
        List<Transaction> recent = transactions.search(TransactionQuery.all().withLimit(8)).stream()
                .filter(t -> t.status().countsInBalance())
                .toList();
        int excluded = (int) accounts.findActive().stream()
                .filter(a -> !a.currency().equals(currency) && byId.containsKey(a.id()))
                .count();

        return new DashboardSummary(netWorth, current, savings, available.computeDefault(), income, expenses,
                upcomingOut, next, recent, excluded);
    }

    private static Predicate<Account> group(AccountType.Group group) {
        return a -> a.type().group() == group;
    }

    private static Money sum(List<AccountBalance> balances, Money zero) {
        return balances.stream().map(AccountBalance::balance).reduce(zero, Money::plus);
    }
}

package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.core.stats.CategoryAmount;
import com.financeapp.core.stats.CategoryComparison;
import com.financeapp.core.stats.MonthSummary;
import com.financeapp.core.stats.StatisticsEngine;
import com.financeapp.core.transaction.Transaction;

import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Analyses : bilans mensuels, repartition par categorie, comparaison de periodes. */
public final class StatisticsService {

    /** Vue d'ensemble d'une analyse mois par mois. */
    public record Report(List<MonthSummary> months, Money averageExpenses, List<CategoryAmount> categories,
                         List<CategoryComparison> comparison, CategoryComparison totalComparison) {
    }

    private final TransactionRepository transactions;
    private final CategoryService categories;
    private final AccountService accounts;
    private final PlanningService planning;
    private final SettingsService settings;
    private final StatisticsEngine engine = new StatisticsEngine();

    public StatisticsService(TransactionRepository transactions, CategoryService categories, AccountService accounts,
                             PlanningService planning, SettingsService settings) {
        this.transactions = transactions;
        this.categories = categories;
        this.accounts = accounts;
        this.planning = planning;
        this.settings = settings;
    }

    public YearMonth currentMonth() {
        return YearMonth.from(planning.today());
    }

    /**
     * @param month    mois analyse (repartition par categorie)
     * @param reference mois de comparaison (en general le precedent)
     * @param history  nombre de mois de l'historique mensuel, se terminant a {@code month}
     */
    public Report report(YearMonth month, YearMonth reference, int history) {
        Currency currency = settings.baseCurrency();
        YearMonth first = month.minusMonths(Math.max(1, history) - 1L);
        YearMonth start = first.isBefore(reference) ? first : reference;
        List<Transaction> all = inBaseCurrency(transactions.findCounted(start.atDay(1), month.atEndOfMonth()), currency);
        List<MonthSummary> months = engine.monthly(all, first, month, currency);
        Map<Long, Long> roots = categories.rootIndex();
        Map<Long, String> names = categories.fullNames();
        List<Transaction> current = inMonth(all, month);
        List<Transaction> previous = inMonth(all, reference);
        // Moyenne sur les mois complets uniquement (le mois en cours fausserait la moyenne).
        List<MonthSummary> complete = months.stream().filter(m -> m.month().isBefore(currentMonth())).toList();
        return new Report(months,
                engine.averageExpenses(complete.isEmpty() ? months : complete, currency),
                engine.byCategory(current, currency, roots::get, names),
                engine.compare(previous, current, currency, roots::get, names),
                engine.total(previous, current, currency));
    }

    /** Principaux commercants / libelles sur une periode (bornes incluses). */
    public List<com.financeapp.core.stats.LabelStat> merchants(java.time.LocalDate from, java.time.LocalDate to, int limit) {
        Currency currency = settings.baseCurrency();
        return engine.byLabel(inBaseCurrency(transactions.findCounted(from, to), currency), currency, limit);
    }

    /** Comparaison de deux periodes quelconques, par categorie, plus le total. */
    public PeriodComparison comparePeriods(java.time.LocalDate refFrom, java.time.LocalDate refTo,
                                           java.time.LocalDate from, java.time.LocalDate to) {
        if (refTo.isBefore(refFrom) || to.isBefore(from)) {
            throw new BusinessException("Période invalide : la fin précède le début");
        }
        Currency currency = settings.baseCurrency();
        List<Transaction> reference = inBaseCurrency(transactions.findCounted(refFrom, refTo), currency);
        List<Transaction> current = inBaseCurrency(transactions.findCounted(from, to), currency);
        Map<Long, Long> roots = categories.rootIndex();
        Map<Long, String> names = categories.fullNames();
        return new PeriodComparison(engine.compare(reference, current, currency, roots::get, names),
                engine.total(reference, current, currency));
    }

    public record PeriodComparison(List<CategoryComparison> categories, CategoryComparison total) {
    }

    private List<Transaction> inBaseCurrency(List<Transaction> list, Currency currency) {
        Set<Long> ids = accounts.findAll().stream().filter(a -> a.currency().equals(currency))
                .map(Account::id).collect(Collectors.toSet());
        return list.stream().filter(t -> ids.contains(t.accountId())).toList();
    }

    private static List<Transaction> inMonth(List<Transaction> list, YearMonth month) {
        return list.stream().filter(t -> YearMonth.from(t.date()).equals(month)).toList();
    }
}

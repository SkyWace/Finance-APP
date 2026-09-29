package com.financeapp.core.stats;

import com.financeapp.core.money.Money;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Agregations pures sur des operations deja comptees dans le solde (effectuees
 * ou en attente). Les virements internes ne sont ni des revenus ni des depenses.
 */
public final class StatisticsEngine {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public List<MonthSummary> monthly(List<Transaction> transactions, YearMonth from, YearMonth to, Currency currency) {
        Money zero = Money.zero(currency);
        Map<YearMonth, Money[]> sums = new LinkedHashMap<>();
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            sums.put(m, new Money[]{zero, zero});
        }
        for (Transaction t : transactions) {
            Money[] s = sums.get(YearMonth.from(t.date()));
            if (s == null || !t.amount().currency().equals(currency)) {
                continue;
            }
            if (t.type() == TransactionType.INCOME) {
                s[0] = s[0].plus(t.amount());
            } else if (t.type() == TransactionType.EXPENSE) {
                s[1] = s[1].plus(t.amount());
            }
        }
        List<MonthSummary> result = new ArrayList<>();
        sums.forEach((month, s) -> {
            Money saved = s[0].plus(s[1]);
            BigDecimal rate = s[0].isPositive()
                    ? saved.amount().multiply(HUNDRED).divide(s[0].amount(), 1, RoundingMode.HALF_EVEN) : null;
            result.add(new MonthSummary(month, s[0], s[1], saved, rate));
        });
        return result;
    }

    /** Moyenne des depenses mensuelles (positive) sur les mois fournis. */
    public Money averageExpenses(List<MonthSummary> months, Currency currency) {
        if (months.isEmpty()) {
            return Money.zero(currency);
        }
        Money total = months.stream().map(MonthSummary::expenses).reduce(Money.zero(currency), Money::plus);
        return total.negate().divide(BigDecimal.valueOf(months.size()));
    }

    /**
     * Depenses par categorie racine, de la plus importante a la plus faible.
     *
     * @param rootOf categorie racine d'une categorie
     */
    public List<CategoryAmount> byCategory(List<Transaction> transactions, Currency currency,
                                           Function<Long, Long> rootOf, Map<Long, String> names) {
        Map<Long, Money> totals = expensesByRoot(transactions, currency, rootOf);
        Money all = totals.values().stream().reduce(Money.zero(currency), Money::plus);
        List<CategoryAmount> result = new ArrayList<>();
        totals.forEach((root, amount) -> result.add(new CategoryAmount(root, nameOf(root, names), amount,
                all.isZero() ? BigDecimal.ZERO
                        : amount.amount().multiply(HUNDRED).divide(all.amount(), 1, RoundingMode.HALF_EVEN))));
        result.sort(Comparator.comparing((CategoryAmount c) -> c.amount().amount()).reversed());
        return result;
    }

    /** Comparaison categorie par categorie entre une periode de reference et la periode courante. */
    public List<CategoryComparison> compare(List<Transaction> reference, List<Transaction> current, Currency currency,
                                            Function<Long, Long> rootOf, Map<Long, String> names) {
        Money zero = Money.zero(currency);
        Map<Long, Money> ref = expensesByRoot(reference, currency, rootOf);
        Map<Long, Money> cur = expensesByRoot(current, currency, rootOf);
        Set<Long> keys = new HashSet<>(ref.keySet());
        keys.addAll(cur.keySet());
        List<CategoryComparison> result = new ArrayList<>();
        for (Long key : keys) {
            result.add(comparison(key, nameOf(key, names), ref.getOrDefault(key, zero), cur.getOrDefault(key, zero)));
        }
        result.sort(Comparator.comparing((CategoryComparison c) -> c.delta().abs().amount()).reversed());
        return result;
    }

    /** Comparaison des depenses totales. */
    public CategoryComparison total(List<Transaction> reference, List<Transaction> current, Currency currency) {
        Money zero = Money.zero(currency);
        Money ref = expensesByRoot(reference, currency, id -> 0L).values().stream().reduce(zero, Money::plus);
        Money cur = expensesByRoot(current, currency, id -> 0L).values().stream().reduce(zero, Money::plus);
        return comparison(null, "Dépenses totales", ref, cur);
    }

    private static CategoryComparison comparison(Long id, String name, Money ref, Money cur) {
        Money delta = cur.minus(ref);
        BigDecimal pct = ref.isZero() ? null
                : delta.amount().multiply(HUNDRED).divide(ref.amount(), 1, RoundingMode.HALF_EVEN);
        return new CategoryComparison(id, name, ref, cur, delta, pct);
    }

    private static Map<Long, Money> expensesByRoot(List<Transaction> transactions, Currency currency,
                                                   Function<Long, Long> rootOf) {
        Map<Long, Money> totals = new HashMap<>();
        for (Transaction t : transactions) {
            if (t.type() != TransactionType.EXPENSE || !t.amount().currency().equals(currency)) {
                continue;
            }
            Long root = t.categoryId() == null ? null : rootOf.apply(t.categoryId());
            totals.merge(root, t.amount().negate(), Money::plus);
        }
        return totals;
    }

    private static String nameOf(Long id, Map<Long, String> names) {
        return id == null ? "Sans catégorie" : Objects.requireNonNullElse(names.get(id), "?");
    }
}

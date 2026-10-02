package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.account.AccountValuation;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.core.transaction.Transaction;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Evolution du patrimoine financier dans le temps : solde de chaque compte actif (de la
 * devise de reference) en fin de mois, puis aujourd'hui.
 *
 * <p>Solde d'un compte a une date : derniere valeur constatee a cette date (releve,
 * valorisation) plus les operations posterieures ; a defaut, solde initial plus les
 * operations jusqu'a cette date. Un compte ne compte qu'a partir de sa date d'ouverture.
 * Le dernier point est exactement le patrimoine du tableau de bord.
 */
public final class NetWorthService {

    /** Patrimoine a une date, et sa repartition. */
    public record Point(LocalDate date, Money total, Money current, Money savings) {
    }

    /**
     * @param points           du plus ancien au plus recent (aujourd'hui en dernier)
     * @param otherCurrencies  comptes dans une autre devise, non comptes
     */
    public record History(List<Point> points, int otherCurrencies) {

        public boolean isEmpty() {
            return points.size() < 2;
        }

        /** Evolution entre le premier et le dernier point. */
        public Money change() {
            return points.getLast().total().minus(points.getFirst().total());
        }
    }

    private final AccountService accounts;
    private final TransactionRepository transactions;
    private final SettingsService settings;
    private final Clock clock;

    public NetWorthService(AccountService accounts, TransactionRepository transactions, SettingsService settings,
                           Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.settings = settings;
        this.clock = clock;
    }

    /** @param months nombre de mois a remonter ; 0 ou moins : depuis l'ouverture du premier compte */
    public History history(int months) {
        Currency currency = settings.baseCurrency();
        LocalDate today = LocalDate.now(clock);
        List<Account> active = accounts.findActive();
        List<Account> counted = active.stream().filter(a -> a.currency().equals(currency)).toList();
        int others = active.size() - counted.size();
        if (counted.isEmpty()) {
            return new History(List.of(), others);
        }

        LocalDate first = counted.stream().map(Account::openingDate).min(Comparator.naturalOrder()).orElseThrow();
        YearMonth start = YearMonth.from(first);
        if (months > 0 && start.isBefore(YearMonth.from(today).minusMonths(months))) {
            start = YearMonth.from(today).minusMonths(months);
        }
        List<LocalDate> dates = new ArrayList<>();
        for (YearMonth m = start; m.isBefore(YearMonth.from(today)); m = m.plusMonths(1)) {
            dates.add(m.atEndOfMonth());
        }

        // Mouvements comptes par compte, cumules par date (une seule lecture).
        Map<Long, TreeMap<LocalDate, Long>> movements = new HashMap<>();
        if (!dates.isEmpty()) {
            LocalDate earliest = LocalDate.of(1900, 1, 1);
            for (Transaction t : transactions.findCounted(earliest, dates.getLast())) {
                movements.computeIfAbsent(t.accountId(), k -> new TreeMap<>())
                        .merge(t.date(), t.amount().toMinorUnits(), Long::sum);
            }
        }

        Money zero = Money.zero(currency);
        List<Point> points = new ArrayList<>();
        Map<Long, List<AccountValuation>> valuations = new HashMap<>();
        for (Account a : counted) {
            List<AccountValuation> list = new ArrayList<>(accounts.valuations(a.id()));
            list.removeIf(v -> !v.value().currency().equals(currency));
            list.sort(Comparator.comparing(AccountValuation::date));
            valuations.put(a.id(), list);
        }
        for (LocalDate date : dates) {
            Money total = zero;
            Money current = zero;
            Money savings = zero;
            for (Account a : counted) {
                if (a.openingDate().isAfter(date)) {
                    continue;
                }
                Money balance = balanceAt(a, date, valuations.get(a.id()), movements.get(a.id()));
                total = total.plus(balance);
                if (a.type().group() == AccountType.Group.CURRENT) {
                    current = current.plus(balance);
                } else if (a.type().group() == AccountType.Group.SAVINGS) {
                    savings = savings.plus(balance);
                }
            }
            points.add(new Point(date, total, current, savings));
        }

        // Aujourd'hui : les soldes actuels, comme le tableau de bord.
        Map<Long, Money> balances = accounts.balances();
        Money total = zero;
        Money current = zero;
        Money savings = zero;
        for (Account a : counted) {
            Money balance = balances.get(a.id());
            total = total.plus(balance);
            if (a.type().group() == AccountType.Group.CURRENT) {
                current = current.plus(balance);
            } else if (a.type().group() == AccountType.Group.SAVINGS) {
                savings = savings.plus(balance);
            }
        }
        points.add(new Point(today, total, current, savings));
        return new History(points, others);
    }

    private static Money balanceAt(Account a, LocalDate date, List<AccountValuation> valuations,
                                   TreeMap<LocalDate, Long> movements) {
        AccountValuation last = null;
        for (AccountValuation v : valuations) {
            if (!v.date().isAfter(date)) {
                last = v;
            }
        }
        long minor = 0;
        if (movements != null) {
            var range = last == null ? movements.headMap(date, true)
                    : movements.subMap(last.date(), false, date, true);
            for (long m : range.values()) {
                minor += m;
            }
        }
        Money base = last == null ? a.initialBalance() : last.value();
        return base.plus(Money.ofMinor(minor, a.currency()));
    }
}

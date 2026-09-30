package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.account.AccountType;
import com.financeapp.core.account.AccountValuation;
import com.financeapp.core.money.Money;
import com.financeapp.core.settings.SettingsService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Map;

/**
 * Vue d'ensemble de l'epargne detenue : livrets, epargne logement, assurance-vie,
 * PEA, PER, epargne salariale... avec la derniere valeur connue et, pour les
 * livrets reglementes, la marge restante sous le plafond de versements.
 */
public final class SavingsService {

    /**
     * Un produit d'epargne.
     *
     * @param lastValuation    derniere valeur saisie ({@code null} : solde calcule sur les operations)
     * @param previousValuation valorisation precedente, pour montrer l'evolution
     * @param ceiling          plafond de versements ({@code null} si aucun)
     * @param room             marge restante sous le plafond (jamais negative)
     * @param ceilingPercent   part du plafond utilisee, en % (peut depasser 100 avec les interets)
     */
    public record Holding(Account account, Money balance, AccountValuation lastValuation,
                          AccountValuation previousValuation, Money ceiling, Money room, BigDecimal ceilingPercent) {

        /** Evolution entre les deux dernieres valeurs saisies ({@code null} s'il n'y en a pas deux). */
        public Money changeSincePrevious() {
            return lastValuation == null || previousValuation == null ? null
                    : lastValuation.value().minus(previousValuation.value());
        }
    }

    /**
     * @param shareOfNetWorth part de l'epargne dans le patrimoine financier, en % ({@code null} si patrimoine nul)
     */
    public record Overview(List<Holding> available, List<Holding> longTerm, Money total, Money totalAvailable,
                           Money totalLongTerm, Money netWorth, BigDecimal shareOfNetWorth) {

        public boolean isEmpty() {
            return available.isEmpty() && longTerm.isEmpty();
        }
    }

    private final AccountService accounts;
    private final SettingsService settings;

    public SavingsService(AccountService accounts, SettingsService settings) {
        this.accounts = accounts;
        this.settings = settings;
    }

    public Overview overview() {
        Currency currency = settings.baseCurrency();
        Map<Long, Money> balances = accounts.balances();
        Money zero = Money.zero(currency);
        List<Holding> available = new ArrayList<>();
        List<Holding> longTerm = new ArrayList<>();
        Money totalAvailable = zero;
        Money totalLongTerm = zero;
        Money netWorth = zero;
        List<Account> active = accounts.findActive().stream()
                .sorted(Comparator.comparingInt(Account::sortOrder).thenComparing(Account::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (Account a : active) {
            Money balance = balances.get(a.id());
            boolean base = a.currency().equals(currency);
            if (base) {
                netWorth = netWorth.plus(balance);
            }
            if (!a.type().isSavings()) {
                continue;
            }
            Holding h = holding(a, balance);
            if (a.type().savings() == AccountType.Savings.AVAILABLE) {
                available.add(h);
                totalAvailable = base ? totalAvailable.plus(balance) : totalAvailable;
            } else {
                longTerm.add(h);
                totalLongTerm = base ? totalLongTerm.plus(balance) : totalLongTerm;
            }
        }
        Money total = totalAvailable.plus(totalLongTerm);
        BigDecimal share = netWorth.isPositive()
                ? total.amount().multiply(BigDecimal.valueOf(100)).divide(netWorth.amount(), 1, RoundingMode.HALF_EVEN)
                : null;
        return new Overview(available, longTerm, total, totalAvailable, totalLongTerm, netWorth, share);
    }

    private Holding holding(Account a, Money balance) {
        List<AccountValuation> history = accounts.valuations(a.id());
        AccountValuation last = history.isEmpty() ? null : history.getFirst();
        AccountValuation previous = history.size() > 1 ? history.get(1) : null;
        BigDecimal limit = a.type().depositCeiling();
        Money ceiling = limit == null ? null : Money.of(limit, a.currency());
        Money room = null;
        BigDecimal percent = null;
        if (ceiling != null) {
            Money left = ceiling.minus(balance);
            room = left.isNegative() ? Money.zero(a.currency()) : left;
            percent = balance.amount().multiply(BigDecimal.valueOf(100)).divide(ceiling.amount(), 1, RoundingMode.HALF_EVEN);
        }
        return new Holding(a, balance, last, previous, ceiling, room, percent);
    }
}

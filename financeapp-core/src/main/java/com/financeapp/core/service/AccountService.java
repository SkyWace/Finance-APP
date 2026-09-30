package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.available.AccountBalance;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.AccountRepository;
import com.financeapp.core.port.RecurringRuleRepository;
import com.financeapp.core.port.ValuationRepository;
import com.financeapp.core.account.AccountValuation;
import java.time.Clock;
import java.time.LocalDate;
import com.financeapp.core.port.TransactionRepository;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Gestion des comptes et calcul de leurs soldes. */
public final class AccountService {

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final RecurringRuleRepository recurringRules;
    private final ValuationRepository valuations;
    private final Clock clock;

    public AccountService(AccountRepository accounts, TransactionRepository transactions,
                          RecurringRuleRepository recurringRules) {
        this(accounts, transactions, recurringRules, null, Clock.systemDefaultZone());
    }

    /** @param valuations valorisations des comptes d'epargne ({@code null} : soldes calcules sur les seules operations) */
    public AccountService(AccountRepository accounts, TransactionRepository transactions,
                          RecurringRuleRepository recurringRules, ValuationRepository valuations, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.recurringRules = recurringRules;
        this.valuations = valuations;
        this.clock = clock;
    }

    public List<Account> findAll() {
        return accounts.findAll();
    }

    public List<Account> findActive() {
        return accounts.findAll().stream().filter(a -> !a.archived()).toList();
    }

    public Account get(long id) {
        return accounts.findById(id).orElseThrow(() -> new BusinessException("Compte introuvable"));
    }

    public Account save(Account account) {
        if (account.id() != null) {
            Account existing = get(account.id());
            if (!existing.currency().equals(account.currency()) && transactions.existsForAccount(account.id())) {
                throw new BusinessException("La devise d'un compte contenant des opérations ne peut pas être modifiée");
            }
        }
        return accounts.save(account);
    }

    public Account setArchived(long id, boolean archived) {
        return accounts.save(get(id).withArchived(archived));
    }

    /** Suppression reservee aux comptes vides ; sinon, il faut archiver pour preserver l'historique. */
    public void delete(long id) {
        boolean usedByRule = recurringRules.findAll().stream()
                .anyMatch(r -> r.accountId() == id || Long.valueOf(id).equals(r.toAccountId()));
        if (transactions.existsForAccount(id) || usedByRule) {
            throw new BusinessException("Ce compte contient des opérations ou des récurrences : archivez-le plutôt que de le supprimer.");
        }
        accounts.delete(id);
    }

    /**
     * Solde actuel de chaque compte : solde initial + operations effectuees et en
     * attente ; pour un compte valorise, derniere valeur constatee + operations
     * posterieures a cette date.
     */
    public Map<Long, Money> balances() {
        Map<Long, Long> sums = transactions.sumCountedMinorByAccount();
        Map<Long, AccountValuation> latest = valuations == null ? Map.of() : valuations.latestByAccount();
        Map<Long, Money> result = new LinkedHashMap<>();
        for (Account a : accounts.findAll()) {
            AccountValuation v = latest.get(a.id());
            if (v != null && v.value().currency().equals(a.currency())) {
                Money after = Money.ofMinor(transactions.sumCountedMinorAfter(a.id(), v.date()), a.currency());
                result.put(a.id(), v.value().plus(after));
            } else {
                Money movements = Money.ofMinor(sums.getOrDefault(a.id(), 0L), a.currency());
                result.put(a.id(), a.initialBalance().plus(movements));
            }
        }
        return result;
    }

    // ------------------------------------------------------------ valorisations

    /**
     * Enregistre la valeur d'un compte a une date (au plus aujourd'hui) : releve
     * d'un livret apres les interets, valeur d'un PEA, d'une assurance-vie...
     */
    public AccountValuation recordValuation(long accountId, LocalDate date, java.math.BigDecimal value) {
        Account account = get(accountId);
        if (valuations == null) {
            throw new BusinessException("Valorisations indisponibles");
        }
        if (date == null || date.isAfter(LocalDate.now(clock))) {
            throw new BusinessException("La date de la valeur ne peut pas être dans le futur");
        }
        if (value == null || value.signum() < 0) {
            throw new BusinessException("La valeur doit être positive ou nulle");
        }
        return valuations.save(new AccountValuation(null, accountId, date, Money.of(value, account.currency())));
    }

    public List<AccountValuation> valuations(long accountId) {
        return valuations == null ? List.of() : valuations.findByAccount(accountId);
    }

    public java.util.Optional<AccountValuation> latestValuation(long accountId) {
        return valuations == null ? java.util.Optional.empty()
                : java.util.Optional.ofNullable(valuations.latestByAccount().get(accountId));
    }

    public void deleteValuation(long id) {
        if (valuations != null) {
            valuations.delete(id);
        }
    }

    public Money balanceOf(long accountId) {
        Money balance = balances().get(accountId);
        if (balance == null) {
            throw new BusinessException("Compte introuvable");
        }
        return balance;
    }

    /** Soldes des comptes actifs de la devise donnee satisfaisant le filtre. */
    public List<AccountBalance> balancesOf(java.util.Currency currency, Predicate<Account> filter) {
        Map<Long, Money> balances = balances();
        return findActive().stream()
                .filter(a -> a.currency().equals(currency))
                .filter(filter)
                .sorted(Comparator.comparingInt(Account::sortOrder))
                .map(a -> new AccountBalance(a.id(), a.name(), balances.get(a.id())))
                .toList();
    }
}

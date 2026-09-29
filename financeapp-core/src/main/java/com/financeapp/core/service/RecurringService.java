package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.port.AccountRepository;
import com.financeapp.core.port.OccurrenceKey;
import com.financeapp.core.port.RecurringRuleRepository;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.recurring.RecurrenceEngine;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Regles recurrentes : leurs occurrences restent virtuelles jusqu'a ce que
 * l'utilisateur les valide (transaction effectuee) ou les ignore (transaction
 * annulee), ce qui les retire des operations a venir.
 */
public final class RecurringService {

    private final RecurringRuleRepository rules;
    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final TransactionService transactionService;
    private final RecurrenceEngine engine;
    private final Clock clock;

    public RecurringService(RecurringRuleRepository rules, TransactionRepository transactions,
                            AccountRepository accounts, TransactionService transactionService,
                            RecurrenceEngine engine, Clock clock) {
        this.rules = rules;
        this.transactions = transactions;
        this.accounts = accounts;
        this.transactionService = transactionService;
        this.engine = engine;
        this.clock = clock;
    }

    public List<RecurringRule> findAll() {
        return rules.findAll();
    }

    public RecurringRule get(long id) {
        return rules.findById(id).orElseThrow(() -> new BusinessException("Récurrence introuvable"));
    }

    /**
     * Enregistre une regle. A la creation, le suivi commence aujourd'hui : une
     * regle "salaire le 28" creee le 29 ne fait pas apparaitre le salaire de la
     * veille comme un revenu encore a venir.
     */
    public RecurringRule save(RecurringRule rule) {
        if (rule.id() == null) {
            LocalDate today = LocalDate.now(clock);
            rule = rule.withTrackedFrom(rule.startDate().isAfter(today) ? rule.startDate() : today);
        }
        Account account = accounts.findById(rule.accountId())
                .orElseThrow(() -> new BusinessException("Compte introuvable"));
        if (!account.currency().equals(rule.amount().currency())) {
            throw new BusinessException("La devise du montant doit être celle du compte");
        }
        if (rule.toAccountId() != null) {
            Account to = accounts.findById(rule.toAccountId())
                    .orElseThrow(() -> new BusinessException("Compte destinataire introuvable"));
            if (!to.currency().equals(account.currency())) {
                throw new BusinessException("Les virements entre devises différentes ne sont pas encore pris en charge");
            }
        }
        return rules.save(rule);
    }

    /** Arrete la regle : plus aucune occurrence apres {@code lastDate}. L'historique est conserve. */
    public RecurringRule end(long id, LocalDate lastDate) {
        RecurringRule rule = get(id);
        LocalDate end = lastDate.isBefore(rule.startDate()) ? rule.startDate() : lastDate;
        return rules.save(rule.withEndDate(end));
    }

    public void delete(long id) {
        rules.delete(id);
    }

    public Optional<LocalDate> nextOccurrence(RecurringRule rule) {
        LocalDate today = LocalDate.now(clock);
        Set<OccurrenceKey> done = transactions.findMaterializedOccurrences(today);
        return engine.occurrences(rule, today, today.plusYears(2)).stream()
                .filter(d -> !done.contains(new OccurrenceKey(rule.id(), d)))
                .findFirst();
    }

    /**
     * Occurrences non encore validees ni ignorees dans [from, to], converties
     * en operations a venir (deux lignes pour un virement : une par compte).
     */
    public List<PlannedItem> pendingOccurrences(LocalDate from, LocalDate to) {
        Set<OccurrenceKey> done = transactions.findMaterializedOccurrences(from);
        List<PlannedItem> items = new ArrayList<>();
        for (RecurringRule rule : rules.findAll()) {
            LocalDate ruleFrom = rule.trackedFrom().isAfter(from) ? rule.trackedFrom() : from;
            for (LocalDate date : engine.occurrences(rule, ruleFrom, to)) {
                if (done.contains(new OccurrenceKey(rule.id(), date))) {
                    continue;
                }
                items.add(new PlannedItem(date, rule.accountId(), rule.label(), rule.signedAmount(), rule.type(),
                        rule.categoryId(), PlannedItem.Source.RECURRING, null, rule.id(), rule.toAccountId(),
                        rule.type() != TransactionType.INCOME || rule.certain()));
                if (rule.type() == TransactionType.TRANSFER) {
                    items.add(new PlannedItem(date, rule.toAccountId(), rule.label(), rule.amount(), rule.type(),
                            null, PlannedItem.Source.RECURRING, null, rule.id(), rule.accountId(), true));
                }
            }
        }
        items.sort(Comparator.comparing(PlannedItem::date));
        return items;
    }

    /**
     * Date de la prochaine paie : prochaine occurrence (strictement apres
     * aujourd'hui) du revenu recurrent le plus eleve parmi les comptes donnes.
     */
    public Optional<LocalDate> nextPayday(Set<Long> accountIds) {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        return rules.findAll().stream()
                .filter(r -> r.type() == TransactionType.INCOME && r.active() && accountIds.contains(r.accountId()))
                .max(Comparator.comparing(r -> r.amount().amount()))
                .flatMap(r -> engine.nextOccurrence(r, tomorrow));
    }

    /**
     * Valide une occurrence : cree la ou les transactions effectuees
     * correspondantes. La date et le montant reels peuvent differer du prevu.
     */
    public List<Transaction> confirm(long ruleId, LocalDate occurrenceDate, LocalDate actualDate, BigDecimal actualAmount) {
        return materialize(get(ruleId), occurrenceDate, actualDate, actualAmount, TransactionStatus.COMPLETED);
    }

    /** Ignore une occurrence (ex. abonnement suspendu ce mois-ci) : trace annulee, sans effet sur le solde. */
    public List<Transaction> skip(long ruleId, LocalDate occurrenceDate) {
        RecurringRule rule = get(ruleId);
        return materialize(rule, occurrenceDate, occurrenceDate, rule.amount().amount(), TransactionStatus.CANCELLED);
    }

    private List<Transaction> materialize(RecurringRule rule, LocalDate occurrence, LocalDate actualDate,
                                          BigDecimal actualAmount, TransactionStatus status) {
        if (!engine.occurrences(rule, occurrence, occurrence).contains(occurrence)) {
            throw new BusinessException("Cette date n'est pas une occurrence de la récurrence");
        }
        if (transactions.findMaterializedOccurrences(occurrence).contains(new OccurrenceKey(rule.id(), occurrence))) {
            throw new BusinessException("Cette occurrence a déjà été traitée");
        }
        if (rule.type() == TransactionType.TRANSFER) {
            TransferDraft draft = new TransferDraft(rule.accountId(), rule.toAccountId(), actualDate, rule.label(),
                    actualAmount, status, rule.note());
            return transactions.insertAll(transactionService.transferLegs(draft, UUID.randomUUID().toString(),
                    null, null, rule.id(), occurrence));
        }
        Money amount = Money.of(actualAmount, rule.amount().currency());
        if (!amount.isPositive()) {
            throw new BusinessException("Le montant doit être strictement positif");
        }
        Money signed = rule.type() == TransactionType.EXPENSE ? amount.negate() : amount;
        Transaction t = new Transaction(null, rule.accountId(), actualDate, rule.label(), signed, rule.type(), status,
                rule.categoryId(), rule.note(), null, null, rule.id(), occurrence);
        return List.of(transactions.insert(t));
    }
}

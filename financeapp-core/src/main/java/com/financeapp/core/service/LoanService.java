package com.financeapp.core.service;

import com.financeapp.core.account.Account;
import com.financeapp.core.loan.AmortizationRow;
import com.financeapp.core.loan.AmortizationSchedule;
import com.financeapp.core.loan.Loan;
import com.financeapp.core.loan.LoanCalculator;
import com.financeapp.core.loan.LoanStatus;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.LoanRepository;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.transaction.TransactionType;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Suivi des credits. Le tableau d'amortissement est calcule, jamais stocke.
 * Les mensualites elles-memes passent par une echeance recurrente liee : c'est
 * elle qui alimente "a venir", le disponible reel et les previsions, sans
 * double comptage.
 */
public final class LoanService {

    private final LoanRepository loans;
    private final RecurringService recurring;
    private final AccountService accounts;
    private final Clock clock;
    private final LoanCalculator calculator = new LoanCalculator();

    public LoanService(LoanRepository loans, RecurringService recurring, AccountService accounts, Clock clock) {
        this.loans = loans;
        this.recurring = recurring;
        this.accounts = accounts;
        this.clock = clock;
    }

    public LoanCalculator calculator() {
        return calculator;
    }

    public List<Loan> findAll() {
        return loans.findAll();
    }

    public Loan get(long id) {
        return loans.findById(id).orElseThrow(() -> new BusinessException("Crédit introuvable"));
    }

    public AmortizationSchedule schedule(Loan loan) {
        try {
            return calculator.schedule(loan);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    /**
     * Enregistre le credit. Avec {@code manageRecurring}, une echeance mensuelle
     * (mensualite + assurance, de la premiere a la derniere mensualite) est creee
     * sur le compte debite. Une echeance deja liee est mise a jour.
     */
    public Loan save(Loan loan, boolean manageRecurring) {
        AmortizationSchedule schedule = validate(loan);
        if (loan.accountId() != null) {
            Account account = accounts.get(loan.accountId());
            if (!account.currency().equals(loan.principal().currency())) {
                throw new BusinessException("La devise du crédit doit être celle du compte débité");
            }
        }
        if (loan.recurringId() != null) {
            Optional<RecurringRule> linked = findRule(loan.recurringId());
            if (linked.isEmpty()) {
                loan = loan.withRecurringId(null);
            } else {
                ensureNotLinkedElsewhere(loan);
                if (manageRecurring) {
                    recurring.save(syncedRule(linked.get(), loan, schedule));
                }
            }
        } else if (manageRecurring) {
            if (loan.accountId() == null) {
                throw new BusinessException("Choisissez le compte débité pour créer l'échéance récurrente");
            }
            RecurringRule rule = recurring.save(new RecurringRule(null, loan.accountId(), null, TransactionType.EXPENSE,
                    loan.name(), schedule.paymentWithInsurance(), loan.categoryId(), Frequency.MONTHLY, 1,
                    loan.firstPaymentDate(), schedule.endDate(), null, true, true, "Mensualité du crédit « " + loan.name() + " »"));
            loan = loan.withRecurringId(rule.id());
        }
        return loans.save(loan);
    }

    public Loan setArchived(long id, boolean archived) {
        return loans.save(get(id).withArchived(archived));
    }

    /** Supprime le credit ; l'echeance recurrente liee et l'historique des paiements sont conserves. */
    public void delete(long id) {
        loans.delete(id);
    }

    /** Situation de chaque credit non archive, a aujourd'hui. */
    public List<LoanStatus> statuses() {
        LocalDate today = LocalDate.now(clock);
        return loans.findAll().stream().filter(l -> !l.archived()).map(l -> status(l, today)).toList();
    }

    public LoanStatus status(Loan loan, LocalDate date) {
        try {
            return calculator.status(loan, date);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Crédit « " + loan.name() + " » : " + e.getMessage());
        }
    }

    /** Capital restant du total (credits non archives dans la devise donnee). */
    public Money totalRemaining(Currency currency) {
        return statuses().stream().map(LoanStatus::remaining).filter(m -> m.currency().equals(currency))
                .reduce(Money.zero(currency), Money::plus);
    }

    /** Echeances de depense pouvant etre liees a ce credit (pas deja liees a un autre). */
    public List<RecurringRule> linkableRules(Long loanId) {
        Set<Long> taken = loans.findAll().stream()
                .filter(l -> l.recurringId() != null && !Objects.equals(l.id(), loanId))
                .map(Loan::recurringId).collect(Collectors.toSet());
        return recurring.findAll().stream()
                .filter(r -> r.type() == TransactionType.EXPENSE && !taken.contains(r.id()))
                .toList();
    }

    private AmortizationSchedule validate(Loan loan) {
        AmortizationSchedule schedule = schedule(loan);
        if (loan.annualRate() != null && loan.payment() != null) {
            AmortizationRow last = schedule.rows().getLast();
            Money computed = calculator.payment(loan.principal(), loan.annualRate(), loan.termMonths());
            if (last.payment().compareTo(loan.payment().plus(loan.payment())) > 0) {
                throw new BusinessException("Avec ce taux, la mensualité saisie ne rembourse pas le capital en "
                        + loan.termMonths() + " mois (mensualité calculée : " + computed.amount().toPlainString().replace('.', ',')
                        + "). Laissez la mensualité vide pour la calculer.");
            }
        }
        return schedule;
    }

    private void ensureNotLinkedElsewhere(Loan loan) {
        boolean taken = loans.findAll().stream()
                .anyMatch(l -> Objects.equals(l.recurringId(), loan.recurringId()) && !Objects.equals(l.id(), loan.id()));
        if (taken) {
            throw new BusinessException("Cette échéance récurrente est déjà liée à un autre crédit");
        }
    }

    private Optional<RecurringRule> findRule(long id) {
        return recurring.findAll().stream().filter(r -> r.id() == id).findFirst();
    }

    /** Aligne montant, dates et libelle de l'echeance liee sur le credit. */
    private static RecurringRule syncedRule(RecurringRule r, Loan loan, AmortizationSchedule schedule) {
        return new RecurringRule(r.id(), r.accountId(), null, TransactionType.EXPENSE, r.label(),
                schedule.paymentWithInsurance(), loan.categoryId() != null ? loan.categoryId() : r.categoryId(),
                Frequency.MONTHLY, 1, loan.firstPaymentDate(), schedule.endDate(), r.trackedFrom(), r.certain(),
                r.active(), r.note());
    }
}

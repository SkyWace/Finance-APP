package com.financeapp.core.service;

import com.financeapp.core.available.Reservation;
import com.financeapp.core.available.ReservationProvider;
import com.financeapp.core.goal.GoalProgress;
import com.financeapp.core.goal.SavingsGoal;
import com.financeapp.core.goal.SavingsGoalCalculator;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.SavingsGoalRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;

/**
 * Objectifs d'epargne. L'epargne accumulee est soit le solde d'un compte
 * dedie, soit un montant tenu a la main.
 */
public final class SavingsGoalService implements ReservationProvider {

    private final SavingsGoalRepository goals;
    private final AccountService accounts;
    private final PlanningService planning;
    private final SavingsGoalCalculator calculator = new SavingsGoalCalculator();

    public SavingsGoalService(SavingsGoalRepository goals, AccountService accounts, PlanningService planning) {
        this.goals = goals;
        this.accounts = accounts;
        this.planning = planning;
    }

    public List<SavingsGoal> findAll() {
        return goals.findAll();
    }

    public SavingsGoal get(long id) {
        return goals.findById(id).orElseThrow(() -> new BusinessException("Objectif introuvable"));
    }

    public SavingsGoal save(SavingsGoal goal) {
        if (goal.linkedAccountId() != null) {
            var account = accounts.get(goal.linkedAccountId());
            if (!account.currency().equals(goal.target().currency())) {
                throw new BusinessException("Le compte lié doit être dans la devise de l'objectif");
            }
        }
        return goals.save(goal);
    }

    public void delete(long id) {
        goals.delete(id);
    }

    /** Ajoute (ou retire, si negatif) un montant a un objectif suivi a la main. */
    public SavingsGoal addContribution(long id, BigDecimal amount) {
        SavingsGoal goal = get(id);
        if (goal.linkedAccountId() != null) {
            throw new BusinessException("Cet objectif suit le solde d'un compte : enregistrez plutôt un virement vers ce compte");
        }
        Money updated = goal.manualSaved().plus(Money.of(amount, goal.target().currency()));
        if (updated.isNegative()) {
            throw new BusinessException("Le montant épargné ne peut pas devenir négatif");
        }
        return goals.save(goal.withManualSaved(updated));
    }

    public List<GoalProgress> progress() {
        Map<Long, Money> balances = accounts.balances();
        LocalDate today = planning.today();
        List<GoalProgress> result = new ArrayList<>();
        for (SavingsGoal g : goals.findAll()) {
            if (!g.archived()) {
                result.add(calculator.progress(g, savedOf(g, balances), today));
            }
        }
        return result;
    }

    /**
     * Pour les objectifs marques "a reserver" : l'effort mensuel necessaire,
     * pour chaque mois entame d'ici l'echeance du calcul.
     */
    @Override
    public List<Reservation> reservations(Currency currency, LocalDate today, LocalDate horizonEnd) {
        List<Reservation> result = new ArrayList<>();
        if (horizonEnd.isBefore(today)) {
            return result;
        }
        long months = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(horizonEnd)) + 1;
        Map<Long, Money> balances = null;
        for (SavingsGoal g : goals.findAll()) {
            if (g.archived() || !g.reserveInAvailable() || !g.target().currency().equals(currency)) {
                continue;
            }
            if (balances == null) {
                balances = accounts.balances();
            }
            GoalProgress p = calculator.progress(g, savedOf(g, balances), today);
            if (p.monthlyNeeded() != null && p.monthlyNeeded().isPositive()) {
                result.add(new Reservation("Objectif " + g.name(),
                        p.monthlyNeeded().multiply(BigDecimal.valueOf(months)), Reservation.Kind.SAVINGS_GOAL));
            }
        }
        return result;
    }

    private static Money savedOf(SavingsGoal g, Map<Long, Money> balances) {
        if (g.linkedAccountId() == null) {
            return g.manualSaved();
        }
        Money balance = balances.get(g.linkedAccountId());
        return balance == null || balance.isNegative() ? Money.zero(g.target().currency()) : balance;
    }
}

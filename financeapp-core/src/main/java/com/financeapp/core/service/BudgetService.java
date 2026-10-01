package com.financeapp.core.service;

import com.financeapp.core.available.Reservation;
import com.financeapp.core.available.ReservationProvider;
import com.financeapp.core.budget.Budget;
import com.financeapp.core.budget.BudgetEngine;
import com.financeapp.core.budget.BudgetProgress;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.port.BudgetRepository;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.settings.SettingsService;
import com.financeapp.core.transaction.SplitLine;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Budgets mensuels par categorie : suivi de la consommation et reservation
 * du reste dans le disponible reel.
 */
public final class BudgetService implements ReservationProvider {

    private final BudgetRepository budgets;
    private final TransactionRepository transactions;
    private final PlanningService planning;
    private final CategoryService categories;
    private final SettingsService settings;
    private final BudgetEngine engine = new BudgetEngine();

    public BudgetService(BudgetRepository budgets, TransactionRepository transactions, PlanningService planning,
                         CategoryService categories, SettingsService settings) {
        this.budgets = budgets;
        this.transactions = transactions;
        this.planning = planning;
        this.categories = categories;
        this.settings = settings;
    }

    public List<Budget> findAll() {
        return budgets.findAll();
    }

    public Budget save(Budget budget) {
        Category category = categories.find(budget.categoryId())
                .orElseThrow(() -> new BusinessException("Catégorie introuvable"));
        if (category.kind() == CategoryKind.INCOME) {
            throw new BusinessException("Un budget porte sur une catégorie de dépenses");
        }
        if (!budget.limit().currency().equals(settings.baseCurrency())) {
            throw new BusinessException("Le budget doit être exprimé dans la devise de référence");
        }
        boolean duplicate = budgets.findAll().stream()
                .anyMatch(b -> b.active() && budget.active() && b.categoryId() == budget.categoryId()
                        && !Objects.equals(b.id(), budget.id()));
        if (duplicate) {
            throw new BusinessException("Cette catégorie a déjà un budget");
        }
        return budgets.save(budget);
    }

    public void delete(long id) {
        budgets.delete(id);
    }

    /** Situation des budgets actifs pour le mois donne, les plus consommes en premier. */
    public List<BudgetProgress> progress(YearMonth month) {
        Currency currency = settings.baseCurrency();
        Map<Long, String> names = categories.fullNames();
        List<Transaction> counted = transactions.findCounted(month.atDay(1), month.atEndOfMonth());
        boolean current = month.equals(YearMonth.from(planning.today()));
        List<PlannedItem> upcoming = current ? planning.upcoming(month.atEndOfMonth()) : List.of();
        Map<Long, List<SplitLine>> plannedSplits = current ? splitsOfPlanned(month.atEndOfMonth()) : Map.of();
        List<BudgetProgress> result = new ArrayList<>();
        for (Budget b : budgets.findAll()) {
            if (!b.active()) {
                continue;
            }
            Set<Long> scope = categories.selfAndChildren(b.categoryId());
            Money spent = spent(counted, scope, currency);
            Money planned = plannedByMonth(upcoming, plannedSplits, scope, currency,
                    planning.today(), month.atEndOfMonth())
                    .getOrDefault(month, Money.zero(currency));
            result.add(engine.progress(b, names.getOrDefault(b.categoryId(), "?"), spent, planned));
        }
        result.sort(Comparator.comparing(BudgetProgress::percent).reversed());
        return result;
    }

    @Override
    public List<Reservation> reservations(Currency currency, LocalDate today, LocalDate horizonEnd) {
        List<Reservation> result = new ArrayList<>();
        List<Budget> reserved = budgets.findAll().stream()
                .filter(b -> b.active() && b.reserveInAvailable() && b.limit().currency().equals(currency))
                .toList();
        if (reserved.isEmpty() || horizonEnd.isBefore(today)) {
            return result;
        }
        YearMonth month = YearMonth.from(today);
        List<Transaction> counted = transactions.findCounted(month.atDay(1), month.atEndOfMonth());
        List<PlannedItem> upcoming = planning.upcoming(horizonEnd);
        Map<Long, List<SplitLine>> plannedSplits = splitsOfPlanned(horizonEnd);
        Map<Long, String> names = categories.fullNames();
        for (Budget b : reserved) {
            Set<Long> scope = categories.selfAndChildren(b.categoryId());
            Money amount = engine.reservation(b, spent(counted, scope, currency),
                    plannedByMonth(upcoming, plannedSplits, scope, currency, today, horizonEnd), today, horizonEnd);
            result.add(new Reservation("Budget " + names.getOrDefault(b.categoryId(), "?") + " (reste)",
                    amount, Reservation.Kind.BUDGET));
        }
        return result;
    }

    /** Ventilation des operations prevues (saisies), par identifiant d'operation. */
    private Map<Long, List<SplitLine>> splitsOfPlanned(LocalDate until) {
        Map<Long, List<SplitLine>> result = new HashMap<>();
        for (Transaction t : transactions.findPlannedUntil(until)) {
            if (t.isSplit()) {
                result.put(t.id(), t.splits());
            }
        }
        return result;
    }

    /** Depenses de la categorie (et de ses sous-categories), parts ventilees comprises. */
    private static Money spent(List<Transaction> counted, Set<Long> scope, Currency currency) {
        return counted.stream()
                .filter(t -> t.type() == TransactionType.EXPENSE && t.amount().currency().equals(currency))
                .flatMap(t -> t.categoryShares().stream())
                .filter(share -> share.categoryId() != null && scope.contains(share.categoryId()))
                .map(share -> share.amount().negate())
                .reduce(Money.zero(currency), Money::plus);
    }

    /** Depenses prevues de la categorie jusqu'a {@code until}, par mois ; les retards comptent pour le mois en cours. */
    private static Map<YearMonth, Money> plannedByMonth(List<PlannedItem> items, Map<Long, List<SplitLine>> splitsOfPlanned,
                                                       Set<Long> scope, Currency currency,
                                                       LocalDate today, LocalDate until) {
        Map<YearMonth, Money> result = new HashMap<>();
        for (PlannedItem i : items) {
            if (i.type() != TransactionType.EXPENSE || !i.amount().currency().equals(currency) || i.date().isAfter(until)) {
                continue;
            }
            Money part = Money.zero(currency);
            List<SplitLine> plannedSplits = i.transactionId() == null ? List.of()
                    : splitsOfPlanned.getOrDefault(i.transactionId(), List.of());
            if (!plannedSplits.isEmpty()) { // operation prevue ventilee : seulement la part de la categorie
                for (SplitLine share : plannedSplits) {
                    if (share.categoryId() != null && scope.contains(share.categoryId())) {
                        part = part.plus(share.amount().negate());
                    }
                }
            } else if (i.categoryId() != null && scope.contains(i.categoryId())) {
                part = i.amount().negate();
            }
            if (part.isZero()) {
                continue;
            }
            YearMonth m = YearMonth.from(i.date().isBefore(today) ? today : i.date());
            result.merge(m, part, Money::plus);
        }
        return result;
    }
}

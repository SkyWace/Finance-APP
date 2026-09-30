package com.financeapp.core.service;

import com.financeapp.core.available.AccountBalance;
import com.financeapp.core.forecast.Forecast;
import com.financeapp.core.forecast.ForecastEngine;
import com.financeapp.core.forecast.ForecastInput;
import com.financeapp.core.money.Money;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.settings.SettingsService;

import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.text.LabelNormalizer;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Prevision du solde du perimetre "disponible" (comptes courants par defaut). */
public final class ForecastService {

    /** Borne haute des requetes "jusqu'a la fin des temps" (LocalDate.MAX ne se compare pas lexicalement). */
    private static final LocalDate FAR_FUTURE = LocalDate.of(9999, 12, 31);

    private final AvailableBalanceService available;
    private final PlanningService planning;
    private final TransactionRepository transactions;
    private final SettingsService settings;
    private final RecurringService recurring;
    private final ForecastEngine engine = new ForecastEngine();

    public ForecastService(AvailableBalanceService available, PlanningService planning,
                           TransactionRepository transactions, SettingsService settings) {
        this(available, planning, transactions, settings, null);
    }

    /**
     * @param recurring recurrences reelles, pour ne pas compter comme "depenses courantes" les paiements passes
     *                  qui correspondent a une recurrence (saisis a la main ou importes sans rapprochement)
     */
    public ForecastService(AvailableBalanceService available, PlanningService planning,
                           TransactionRepository transactions, SettingsService settings, RecurringService recurring) {
        this.recurring = recurring;
        this.available = available;
        this.planning = planning;
        this.transactions = transactions;
        this.settings = settings;
    }

    /** Nombre de mois complets observes pour estimer les depenses courantes. */
    public static final int VARIABLE_WINDOW_MONTHS = 3;
    private static final BigDecimal RECURRING_TOLERANCE = new BigDecimal("0.10");

    /**
     * Point de depart commun des previsions et des simulations.
     *
     * @param planned operations a venir (prevues et recurrentes) jusqu'a la fin demandee
     */
    public record Baseline(Set<Long> scope, Money currentBalance, LocalDate today, List<PlannedItem> planned,
                           Money variableMonthly) {
    }

    /**
     * @param historyDays nombre de jours d'historique reel avant aujourd'hui
     * @param days        nombre de jours de projection apres aujourd'hui
     */
    public Forecast forecast(int historyDays, int days) {
        return forecast(historyDays, days, false);
    }

    /**
     * @param includeVariable deduire aussi les depenses courantes estimees
     *                        ({@link #variableMonthlyEstimate()}) reparties jour par jour
     */
    public Forecast forecast(int historyDays, int days, boolean includeVariable) {
        LocalDate today = planning.today();
        LocalDate historyFrom = today.minusDays(Math.max(0, historyDays));
        LocalDate until = today.plusDays(Math.max(0, days));
        Baseline b = baseline(until);
        ForecastInput input = new ForecastInput(b.scope(), b.currentBalance(), today, historyFrom,
                transactions.findCounted(historyFrom.plusDays(1), FAR_FUTURE),
                until, b.planned(), includeVariable ? b.variableMonthly() : null);
        return engine.compute(input);
    }

    public Baseline baseline(LocalDate until) {
        LocalDate today = planning.today();
        List<AccountBalance> scope = available.scope();
        Set<Long> ids = scope.stream().map(AccountBalance::accountId).collect(Collectors.toSet());
        Money current = scope.stream().map(AccountBalance::balance)
                .reduce(Money.zero(settings.baseCurrency()), Money::plus);
        return new Baseline(ids, current, today, planning.upcoming(until), variableMonthlyEstimate(ids));
    }

    /** Depenses courantes estimees par mois pour le perimetre du disponible. */
    public Money variableMonthlyEstimate() {
        return variableMonthlyEstimate(available.scope().stream().map(AccountBalance::accountId)
                .collect(Collectors.toSet()));
    }

    /**
     * Moyenne mensuelle des depenses effectuees <em>hors recurrences</em> (courses,
     * loisirs, carburant…) sur les {@value #VARIABLE_WINDOW_MONTHS} derniers mois
     * complets. Seuls les mois ou au moins une operation existe comptent dans la
     * moyenne (historique recent).
     *
     * <p>Une depense passee est consideree comme recurrente (donc exclue, car deja
     * projetee par sa recurrence) si elle est liee a une occurrence, ou si elle
     * ressemble a une recurrence de depense active du meme compte : montant a
     * &plusmn; 10 % et libelle equivalent (ex. "PRLV SEPA LOYER JUILLET" pour la
     * recurrence "Loyer").
     */
    private Money variableMonthlyEstimate(Set<Long> scope) {
        Currency currency = settings.baseCurrency();
        YearMonth current = YearMonth.from(planning.today());
        YearMonth first = current.minusMonths(VARIABLE_WINDOW_MONTHS);
        List<RecurringRule> rules = recurring == null ? List.of() : recurring.findAll().stream()
                .filter(r -> r.active() && r.type() == TransactionType.EXPENSE).toList();
        List<Transaction> window = transactions.findCounted(first.atDay(1), current.minusMonths(1).atEndOfMonth());
        Set<YearMonth> active = new HashSet<>();
        Money total = Money.zero(currency);
        for (Transaction t : window) {
            if (!scope.contains(t.accountId())) {
                continue;
            }
            active.add(YearMonth.from(t.date()));
            if (t.type() == TransactionType.EXPENSE && t.recurringId() == null && t.amount().isNegative()
                    && t.amount().currency().equals(currency) && !looksRecurring(t, rules)) {
                total = total.plus(t.amount().negate());
            }
        }
        return active.isEmpty() ? Money.zero(currency) : total.divide(BigDecimal.valueOf(active.size()));
    }

    private static boolean looksRecurring(Transaction t, List<RecurringRule> rules) {
        BigDecimal paid = t.amount().amount().abs();
        String label = LabelNormalizer.normalize(t.label());
        for (RecurringRule r : rules) {
            if (r.accountId() != t.accountId()) {
                continue;
            }
            BigDecimal expected = r.amount().amount();
            BigDecimal tolerance = expected.multiply(RECURRING_TOLERANCE);
            if (paid.subtract(expected).abs().compareTo(tolerance) > 0) {
                continue;
            }
            String ruleLabel = LabelNormalizer.normalize(r.label());
            if (!label.isBlank() && !ruleLabel.isBlank()
                    && (LabelNormalizer.containsWords(label, ruleLabel) || LabelNormalizer.containsWords(ruleLabel, label))) {
                return true;
            }
        }
        return false;
    }
}

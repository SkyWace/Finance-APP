package com.financeapp.core.simulation;

import com.financeapp.core.forecast.Forecast;
import com.financeapp.core.forecast.ForecastEngine;
import com.financeapp.core.forecast.ForecastInput;
import com.financeapp.core.loan.AmortizationRow;
import com.financeapp.core.loan.AmortizationSchedule;
import com.financeapp.core.loan.LoanCalculator;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Moteur "What If?" : applique des hypotheses a une <em>copie</em> des operations
 * a venir et compare la situation avant / apres. Fonction pure : aucune donnee
 * reelle n'est lue ni ecrite ici, tout arrive par {@link SimulationInput}.
 *
 * <p>Periode analysee : les {@code horizonMonths} mois complets qui suivent le mois
 * en cours (un mois partiel fausserait les moyennes). La courbe de solde part,
 * elle, d'aujourd'hui.
 */
public final class SimulationEngine {

    private final ForecastEngine forecastEngine = new ForecastEngine();
    private final LoanCalculator loanCalculator = new LoanCalculator();

    public SimulationResult run(SimulationInput in) {
        if (in.scope().isEmpty()) {
            throw new IllegalArgumentException("Aucun compte n'est inclus dans le disponible : rien à simuler");
        }
        Simulation sim = in.simulation();
        Currency currency = in.currentBalance().currency();
        YearMonth first = YearMonth.from(in.today()).plusMonths(1);
        YearMonth last = first.plusMonths(sim.horizonMonths() - 1L);
        LocalDate until = last.atEndOfMonth();
        long account = Collections.min(in.scope());

        List<PlannedItem> baseline = in.baseline().stream().filter(p -> !p.date().isAfter(until)).toList();
        List<PlannedItem> removed = new ArrayList<>();
        List<PlannedItem> flows = new ArrayList<>();
        List<LoanPreview> loans = new ArrayList<>();
        List<PlannedItem> scenario = new ArrayList<>(baseline);

        for (SimulationItem item : sim.items()) {
            switch (item.kind()) {
                case ONE_TIME -> flows.add(flow(account, item.date().isBefore(in.today()) ? in.today() : item.date(),
                        item.label(), item.amount(), PlannedItem.Source.PLANNED_TRANSACTION));
                case MONTHLY -> {
                    for (int k = 0; item.months() == null || k < item.months(); k++) {
                        LocalDate d = item.date().plusMonths(k);
                        if (d.isAfter(until)) {
                            break;
                        }
                        if (!d.isBefore(in.today())) {
                            flows.add(flow(account, d, item.label(), item.amount(), PlannedItem.Source.RECURRING));
                        }
                    }
                }
                case LOAN -> {
                    AmortizationSchedule s = loanCalculator.schedule(item.amount(), item.annualRate(), item.payment(),
                            item.months(), item.date(), Money.zero(currency));
                    loans.add(new LoanPreview(item.label(), item.amount(), s.payment(), s.annualRate(), s.rateEstimated(),
                            s.totalInterest(), s.endDate()));
                    for (AmortizationRow row : s.rows()) {
                        if (row.date().isAfter(until)) {
                            break;
                        }
                        if (!row.date().isBefore(in.today())) {
                            flows.add(flow(account, row.date(), item.label(), row.totalPaid().negate(),
                                    PlannedItem.Source.RECURRING));
                        }
                    }
                }
                case STOP_RECURRING -> {
                    List<PlannedItem> stopped = scenario.stream()
                            .filter(p -> Objects.equals(p.recurringId(), item.recurringId())
                                    && !p.date().isBefore(item.date()))
                            .toList();
                    scenario.removeAll(stopped);
                    removed.addAll(stopped);
                }
            }
        }
        scenario.addAll(flows);

        Map<YearMonth, Buckets> before = buckets(baseline, in, first, last);
        Map<YearMonth, Buckets> after = buckets(scenario, in, first, last);
        List<MonthComparison> months = new ArrayList<>();
        for (YearMonth m : before.keySet()) {
            months.add(new MonthComparison(m, before.get(m).picture(in.variableMonthly()).available(),
                    after.get(m).picture(in.variableMonthly()).available()));
        }

        Forecast baseForecast = forecast(in, baseline, until);
        Forecast scenarioForecast = forecast(in, scenario, until);
        return new SimulationResult(first, last, average(before, in.variableMonthly(), currency),
                average(after, in.variableMonthly(), currency), months, baseForecast, scenarioForecast, in.goals(),
                loans, flows, removed);
    }

    private Forecast forecast(SimulationInput in, List<PlannedItem> planned, LocalDate until) {
        return forecastEngine.compute(new ForecastInput(in.scope(), in.currentBalance(), in.today(), in.today(),
                List.of(), until, planned, in.variableMonthly()));
    }

    private static PlannedItem flow(long account, LocalDate date, String label, Money amount, PlannedItem.Source source) {
        TransactionType type = amount.isNegative() ? TransactionType.EXPENSE : TransactionType.INCOME;
        return new PlannedItem(date, account, label, amount, type, null, source, null, null, null, true);
    }

    /** Repartition des flux du perimetre par mois ; les virements internes au perimetre sont neutres. */
    private static Map<YearMonth, Buckets> buckets(List<PlannedItem> items, SimulationInput in,
                                                   YearMonth first, YearMonth last) {
        Currency currency = in.currentBalance().currency();
        Map<YearMonth, Buckets> result = new LinkedHashMap<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            result.put(m, new Buckets(currency));
        }
        for (PlannedItem p : items) {
            Buckets b = result.get(YearMonth.from(p.date()));
            if (b == null || !in.scope().contains(p.accountId())) {
                continue;
            }
            if (p.type() == TransactionType.TRANSFER) {
                if (p.transferAccountId() == null || !in.scope().contains(p.transferAccountId())) {
                    b.savings = b.savings.minus(p.amount());
                }
            } else if (p.amount().isPositive()) {
                b.income = b.income.plus(p.amount());
            } else if (p.source() == PlannedItem.Source.RECURRING) {
                b.fixed = b.fixed.minus(p.amount());
            } else {
                b.oneOff = b.oneOff.minus(p.amount());
            }
        }
        return result;
    }

    private static MonthlyPicture average(Map<YearMonth, Buckets> months, Money variable, Currency currency) {
        Money income = Money.zero(currency);
        Money fixed = Money.zero(currency);
        Money oneOff = Money.zero(currency);
        Money savings = Money.zero(currency);
        for (Buckets b : months.values()) {
            income = income.plus(b.income);
            fixed = fixed.plus(b.fixed);
            oneOff = oneOff.plus(b.oneOff);
            savings = savings.plus(b.savings);
        }
        BigDecimal n = BigDecimal.valueOf(months.size());
        return new MonthlyPicture(income.divide(n), fixed.divide(n), oneOff.divide(n), variable, savings.divide(n));
    }

    private static final class Buckets {
        Money income;
        Money fixed;
        Money oneOff;
        Money savings;

        Buckets(Currency currency) {
            income = fixed = oneOff = savings = Money.zero(currency);
        }

        MonthlyPicture picture(Money variable) {
            return new MonthlyPicture(income, fixed, oneOff, variable, savings);
        }
    }
}

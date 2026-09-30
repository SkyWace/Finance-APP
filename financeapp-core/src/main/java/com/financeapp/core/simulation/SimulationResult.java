package com.financeapp.core.simulation;

import com.financeapp.core.forecast.Forecast;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;

import java.time.YearMonth;
import java.util.List;

/**
 * Resultat d'une simulation : avant / apres, sur les mois {@code firstMonth}..{@code lastMonth}.
 *
 * @param flows operations fictives generees par les hypotheses (pour l'explication)
 */
public record SimulationResult(
        YearMonth firstMonth,
        YearMonth lastMonth,
        MonthlyPicture before,
        MonthlyPicture after,
        List<MonthComparison> months,
        Forecast baselineForecast,
        Forecast scenarioForecast,
        List<GoalImpact> goals,
        List<LoanPreview> loans,
        List<PlannedItem> flows,
        List<PlannedItem> removed) {

    public Money availableImpact() {
        return after.available().minus(before.available());
    }

    public Money goalsMonthlyNeeded() {
        return goals.stream().map(GoalImpact::monthlyNeeded)
                .reduce(Money.zero(before.income().currency()), Money::plus);
    }
}

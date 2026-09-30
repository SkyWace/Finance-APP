package com.financeapp.core.simulation;

import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Donnees de depart d'une simulation (copie en lecture seule de la situation reelle).
 *
 * @param baseline        operations a venir reelles jusqu'a la fin de l'horizon
 * @param variableMonthly depenses courantes estimees par mois
 */
public record SimulationInput(Set<Long> scope, Money currentBalance, LocalDate today, List<PlannedItem> baseline,
                              Money variableMonthly, List<GoalImpact> goals, Simulation simulation) {

    public SimulationInput {
        scope = Set.copyOf(scope);
        baseline = List.copyOf(baseline);
        goals = List.copyOf(goals);
    }
}

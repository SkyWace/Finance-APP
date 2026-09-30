package com.financeapp.core.service;

import com.financeapp.core.goal.GoalProgress;
import com.financeapp.core.port.SimulationRepository;
import com.financeapp.core.simulation.GoalImpact;
import com.financeapp.core.simulation.Simulation;
import com.financeapp.core.simulation.SimulationEngine;
import com.financeapp.core.simulation.SimulationInput;
import com.financeapp.core.simulation.SimulationResult;

import java.time.YearMonth;
import java.util.List;

/**
 * Simulations "What If?". Lit la situation reelle (soldes, operations a venir,
 * objectifs) et la transmet au moteur sous forme de copie : une simulation ne
 * modifie jamais les transactions, recurrences, budgets ou objectifs reels.
 * Seul le scenario lui-meme est enregistre.
 */
public final class SimulationService {

    private final SimulationRepository simulations;
    private final ForecastService forecast;
    private final SavingsGoalService goals;
    private final PlanningService planning;
    private final SimulationEngine engine = new SimulationEngine();

    public SimulationService(SimulationRepository simulations, ForecastService forecast, SavingsGoalService goals,
                             PlanningService planning) {
        this.simulations = simulations;
        this.planning = planning;
        this.forecast = forecast;
        this.goals = goals;
    }

    public List<Simulation> findAll() {
        return simulations.findAll();
    }

    public Simulation get(long id) {
        return simulations.findById(id).orElseThrow(() -> new BusinessException("Simulation introuvable"));
    }

    public Simulation save(Simulation simulation) {
        return simulations.save(simulation);
    }

    public void delete(long id) {
        simulations.delete(id);
    }

    /** Calcule le scenario (enregistre ou non). */
    public SimulationResult run(Simulation simulation) {
        YearMonth last = YearMonth.from(planning.today()).plusMonths(simulation.horizonMonths());
        ForecastService.Baseline b = forecast.baseline(last.atEndOfMonth());
        List<GoalImpact> goalImpacts = goals.progress().stream()
                .filter(p -> !p.goal().archived() && !p.reached() && p.monthlyNeeded() != null)
                .map(SimulationService::impact)
                .toList();
        try {
            return engine.run(new SimulationInput(b.scope(), b.currentBalance(), b.today(), b.planned(),
                    b.variableMonthly(), goalImpacts, simulation));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    private static GoalImpact impact(GoalProgress p) {
        return new GoalImpact(p.goal().name(), p.monthlyNeeded(), p.goal().targetDate());
    }
}

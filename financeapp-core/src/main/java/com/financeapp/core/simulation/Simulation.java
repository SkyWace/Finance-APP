package com.financeapp.core.simulation;

import java.util.List;

/** Scenario "What If?" enregistre : un nom, un horizon et des hypotheses. */
public record Simulation(Long id, String name, int horizonMonths, List<SimulationItem> items) {

    public static final List<Integer> HORIZONS = List.of(12, 24, 48);

    public Simulation {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Le nom de la simulation est obligatoire");
        }
        name = name.strip();
        if (horizonMonths < 1 || horizonMonths > 120) {
            throw new IllegalArgumentException("Horizon de simulation invalide");
        }
        items = List.copyOf(items);
    }

    public Simulation withId(long newId) {
        return new Simulation(newId, name, horizonMonths, items);
    }

    public Simulation withItems(List<SimulationItem> newItems) {
        return new Simulation(id, name, horizonMonths, newItems);
    }
}

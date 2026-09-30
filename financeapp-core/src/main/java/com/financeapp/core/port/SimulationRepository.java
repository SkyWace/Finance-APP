package com.financeapp.core.port;

import com.financeapp.core.simulation.Simulation;

import java.util.List;
import java.util.Optional;

/** Scenarios enregistres. Ils vivent dans leurs propres tables, jamais dans les transactions. */
public interface SimulationRepository {

    List<Simulation> findAll();

    Optional<Simulation> findById(long id);

    /** Enregistre le scenario et remplace toutes ses hypotheses. */
    Simulation save(Simulation simulation);

    void delete(long id);
}

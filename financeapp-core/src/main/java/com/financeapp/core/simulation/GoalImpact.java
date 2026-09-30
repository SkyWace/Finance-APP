package com.financeapp.core.simulation;

import com.financeapp.core.money.Money;

import java.time.LocalDate;

/** Objectif d'epargne en cours et l'effort mensuel qu'il demande. */
public record GoalImpact(String name, Money monthlyNeeded, LocalDate targetDate) {
}

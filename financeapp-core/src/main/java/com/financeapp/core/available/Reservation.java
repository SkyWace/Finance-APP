package com.financeapp.core.available;

import com.financeapp.core.money.Money;

/**
 * Somme a mettre de cote avant de considerer l'argent comme disponible :
 * reste d'un budget variable (courses, carburant...), versement prevu vers
 * un objectif d'epargne, etc. Montant positif = somme reservee.
 */
public record Reservation(String label, Money amount, Kind kind) {

    public enum Kind { BUDGET, SAVINGS_GOAL, OTHER }

    public Reservation {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("Une réservation est un montant positif");
        }
    }
}

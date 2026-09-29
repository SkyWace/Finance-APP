package com.financeapp.core.budget;

import com.financeapp.core.money.Money;

import java.util.Objects;

/**
 * Budget mensuel d'une categorie (sous-categories comprises).
 *
 * @param reserveInAvailable reserver le reste du budget dans le calcul du disponible reel
 */
public record Budget(Long id, long categoryId, Money limit, boolean reserveInAvailable, boolean active) {

    public Budget {
        Objects.requireNonNull(limit, "limit");
        if (!limit.isPositive()) {
            throw new IllegalArgumentException("Le montant du budget doit être strictement positif");
        }
    }

    public Budget withId(long newId) {
        return new Budget(newId, categoryId, limit, reserveInAvailable, active);
    }
}

package com.financeapp.core.goal;

import com.financeapp.core.money.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Objectif d'epargne.
 *
 * @param targetDate         echeance souhaitee ({@code null} = sans echeance)
 * @param linkedAccountId    compte dont le solde represente l'epargne accumulee (ex. un livret dedie) ;
 *                           {@code null} = montant suivi a la main ({@code manualSaved})
 * @param reserveInAvailable reserver chaque mois l'effort necessaire dans le disponible reel
 *                           (a laisser desactive si un virement recurrent alimente deja l'objectif)
 */
public record SavingsGoal(
        Long id,
        String name,
        Money target,
        LocalDate targetDate,
        Long linkedAccountId,
        Money manualSaved,
        boolean reserveInAvailable,
        boolean archived) {

    public SavingsGoal {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(manualSaved, "manualSaved");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Le nom de l'objectif est obligatoire");
        }
        name = name.strip();
        if (!target.isPositive()) {
            throw new IllegalArgumentException("Le montant visé doit être strictement positif");
        }
        if (manualSaved.isNegative()) {
            throw new IllegalArgumentException("Le montant épargné ne peut pas être négatif");
        }
    }

    public SavingsGoal withId(long newId) {
        return new SavingsGoal(newId, name, target, targetDate, linkedAccountId, manualSaved, reserveInAvailable, archived);
    }

    public SavingsGoal withManualSaved(Money saved) {
        return new SavingsGoal(id, name, target, targetDate, linkedAccountId, saved, reserveInAvailable, archived);
    }

    public SavingsGoal withArchived(boolean value) {
        return new SavingsGoal(id, name, target, targetDate, linkedAccountId, manualSaved, reserveInAvailable, value);
    }
}

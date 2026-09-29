package com.financeapp.core.goal;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;

/**
 * Avancement d'un objectif.
 *
 * @param percent        part atteinte, en % (une decimale, plafonnee a 100)
 * @param monthsLeft     mois restants jusqu'au mois de l'echeance inclus ({@code null} sans echeance)
 * @param monthlyNeeded  epargne mensuelle necessaire ({@code null} sans echeance ou si atteint)
 * @param overdue        echeance depassee sans que l'objectif soit atteint
 */
public record GoalProgress(SavingsGoal goal, Money saved, Money remaining, BigDecimal percent,
                           Integer monthsLeft, Money monthlyNeeded, boolean reached, boolean overdue) {
}

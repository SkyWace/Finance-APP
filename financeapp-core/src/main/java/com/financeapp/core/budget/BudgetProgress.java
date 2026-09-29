package com.financeapp.core.budget;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;

/**
 * Situation d'un budget sur le mois.
 *
 * @param spent     depenses effectuees ou en attente du mois (montant positif)
 * @param planned   depenses encore prevues dans le mois (prevues + recurrentes, montant positif)
 * @param remaining limite - depense (negatif en cas de depassement)
 * @param percent   part consommee de la limite, en % (une decimale, peut depasser 100)
 */
public record BudgetProgress(Budget budget, String categoryName, Money spent, Money planned,
                             Money remaining, BigDecimal percent, BudgetStatus status) {
}

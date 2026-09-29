package com.financeapp.core.stats;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;

/**
 * Depenses d'une categorie racine (sous-categories regroupees).
 *
 * @param categoryId {@code null} pour les depenses sans categorie
 * @param amount     montant depense (positif)
 * @param share      part du total, en %
 */
public record CategoryAmount(Long categoryId, String name, Money amount, BigDecimal share) {
}

package com.financeapp.core.stats;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;

/**
 * Evolution des depenses d'une categorie entre deux periodes. Le montant
 * absolu accompagne toujours le pourcentage (un +50 % sur 4 EUR n'a pas le
 * meme sens qu'un +50 % sur 400 EUR).
 *
 * @param percentChange evolution en % ({@code null} si la periode de reference est a zero)
 */
public record CategoryComparison(Long categoryId, String name, Money reference, Money current,
                                 Money delta, BigDecimal percentChange) {
}

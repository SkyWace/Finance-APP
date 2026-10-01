package com.financeapp.core.transaction;

import com.financeapp.core.money.Money;

import java.util.Objects;

/**
 * Ligne de ventilation d'une operation sur une categorie.
 *
 * @param categoryId categorie ({@code null} : sans categorie)
 * @param amount     part de l'operation, du meme signe et de la meme devise qu'elle
 */
public record SplitLine(Long categoryId, Money amount) {

    public SplitLine {
        Objects.requireNonNull(amount, "amount");
        if (amount.isZero()) {
            throw new IllegalArgumentException("Une ligne de ventilation ne peut pas être nulle");
        }
    }
}

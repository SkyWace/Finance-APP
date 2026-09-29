package com.financeapp.core.forecast;

import com.financeapp.core.money.Money;

import java.time.LocalDate;

/**
 * Solde (en fin de journee) a une date donnee.
 *
 * @param projected {@code false} pour l'historique reel, {@code true} pour la projection
 */
public record ForecastPoint(LocalDate date, Money balance, boolean projected) {
}

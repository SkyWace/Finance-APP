package com.financeapp.core.simulation;

import com.financeapp.core.money.Money;

import java.time.YearMonth;

/** Disponible d'un mois, sans et avec la simulation. */
public record MonthComparison(YearMonth month, Money before, Money after) {

    public Money impact() {
        return after.minus(before);
    }
}

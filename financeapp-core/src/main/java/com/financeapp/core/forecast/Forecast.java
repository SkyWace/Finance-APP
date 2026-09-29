package com.financeapp.core.forecast;

import com.financeapp.core.money.Money;

import java.util.List;
import java.util.Optional;

/**
 * Serie quotidienne de soldes : historique reel puis projection. Le point
 * d'aujourd'hui figure dans les deux series pour qu'elles se raccordent.
 *
 * @param lowestProjected point le plus bas de la projection
 * @param firstNegative   premier jour projete ou le solde passe sous zero, s'il existe
 */
public record Forecast(
        List<ForecastPoint> history,
        List<ForecastPoint> projection,
        ForecastPoint lowestProjected,
        Optional<ForecastPoint> firstNegative) {

    public Money endBalance() {
        return projection.getLast().balance();
    }
}

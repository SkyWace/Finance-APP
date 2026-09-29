package com.financeapp.core.available;

import com.financeapp.core.planning.PlannedItem;

import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Entrees du calcul du disponible reel.
 *
 * @param balances             soldes des comptes du perimetre (definit le perimetre)
 * @param horizonEnd           derniere date prise en compte (incluse)
 * @param plannedItems         operations a venir ; celles hors perimetre ou apres l'echeance sont ignorees
 * @param includeCertainIncome ajouter les revenus prevus marques comme certains
 */
public record AvailableBalanceInput(
        Currency currency,
        LocalDate today,
        LocalDate horizonEnd,
        List<AccountBalance> balances,
        List<PlannedItem> plannedItems,
        List<Reservation> reservations,
        boolean includeCertainIncome) {

    public AvailableBalanceInput {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(horizonEnd, "horizonEnd");
        balances = List.copyOf(balances);
        plannedItems = List.copyOf(plannedItems);
        reservations = List.copyOf(reservations);
    }
}

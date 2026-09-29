package com.financeapp.core.calendar;

import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.Transaction;

import java.time.LocalDate;
import java.util.List;

/**
 * Une journee du calendrier financier.
 *
 * @param balance   solde en fin de journee des comptes du disponible (reel pour le passe, prevu ensuite)
 * @param projected {@code true} si {@code balance} est une prevision
 */
public record CalendarDay(LocalDate date, List<Transaction> realized, List<PlannedItem> planned,
                          Money balance, boolean projected) {

    public boolean isEmpty() {
        return realized.isEmpty() && planned.isEmpty();
    }
}

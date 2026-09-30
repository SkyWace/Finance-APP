package com.financeapp.core.forecast;

import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.Transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Entrees de la prevision de solde d'un ensemble de comptes.
 *
 * @param scope          comptes concernes ; les virements internes a ce perimetre sont neutres
 * @param currentBalance solde actuel cumule du perimetre
 * @param historyFrom    debut de l'historique reel a reconstituer (inclus, &lt;= today)
 * @param realized       transactions comptees dans le solde et datees apres {@code historyFrom}
 * @param until          fin de la projection (incluse)
 * @param planned        operations a venir ; celles en retard s'appliquent aujourd'hui
 * @param variableMonthly depenses courantes non planifiees estimees par mois (positif ou zero), reparties
 *                        jour par jour a partir de demain
 */
public record ForecastInput(
        Set<Long> scope,
        Money currentBalance,
        LocalDate today,
        LocalDate historyFrom,
        List<Transaction> realized,
        LocalDate until,
        List<PlannedItem> planned,
        Money variableMonthly) {

    public ForecastInput(Set<Long> scope, Money currentBalance, LocalDate today, LocalDate historyFrom,
                         List<Transaction> realized, LocalDate until, List<PlannedItem> planned) {
        this(scope, currentBalance, today, historyFrom, realized, until, planned, null);
    }

    public ForecastInput {
        Objects.requireNonNull(currentBalance, "currentBalance");
        Objects.requireNonNull(today, "today");
        scope = Set.copyOf(scope);
        realized = List.copyOf(realized);
        planned = List.copyOf(planned);
        if (variableMonthly == null) {
            variableMonthly = Money.zero(currentBalance.currency());
        }
        if (variableMonthly.isNegative()) {
            throw new IllegalArgumentException("Estimation des depenses variables negative");
        }
        if (historyFrom.isAfter(today) || until.isBefore(today)) {
            throw new IllegalArgumentException("Periode incoherente");
        }
    }
}

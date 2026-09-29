package com.financeapp.core.dashboard;

import com.financeapp.core.available.AvailableBalanceResult;
import com.financeapp.core.money.Money;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.Transaction;

import java.util.List;

/**
 * Indicateurs du tableau de bord, tous dans la devise de reference.
 *
 * @param upcomingThisMonth     somme des sorties prevues d'ici la fin du mois (virements internes exclus)
 * @param excludedAccounts      nombre de comptes actifs dans une autre devise, exclus des totaux
 */
public record DashboardSummary(
        Money netWorth,
        Money currentAccounts,
        Money savings,
        AvailableBalanceResult available,
        Money monthIncome,
        Money monthExpenses,
        Money upcomingThisMonth,
        List<PlannedItem> nextOperations,
        List<Transaction> recentTransactions,
        int excludedAccounts) {
}

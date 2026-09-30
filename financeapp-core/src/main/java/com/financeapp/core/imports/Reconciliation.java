package com.financeapp.core.imports;

import com.financeapp.core.transaction.TransactionStatus;

import java.time.LocalDate;

/**
 * Operation prevue realisee par une ligne importee. L'etat precedent est
 * conserve pour pouvoir annuler l'import.
 */
public record Reconciliation(long transactionId, LocalDate newDate, String newLabel,
                             TransactionStatus previousStatus, LocalDate previousDate, String previousLabel) {
}

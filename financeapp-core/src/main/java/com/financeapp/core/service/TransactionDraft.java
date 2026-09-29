package com.financeapp.core.service;

import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Saisie d'un revenu ou d'une depense. Le montant est saisi en valeur
 * absolue ; le signe est deduit du type, la devise du compte.
 */
public record TransactionDraft(
        long accountId,
        LocalDate date,
        String label,
        BigDecimal amount,
        TransactionType type,
        TransactionStatus status,
        Long categoryId,
        String note) {
}

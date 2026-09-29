package com.financeapp.core.service;

import com.financeapp.core.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Saisie d'un virement interne ; montant positif, debite de {@code fromAccountId}. */
public record TransferDraft(
        long fromAccountId,
        long toAccountId,
        LocalDate date,
        String label,
        BigDecimal amount,
        TransactionStatus status,
        String note) {
}

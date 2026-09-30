package com.financeapp.core.imports;

import com.financeapp.core.transaction.Transaction;

/**
 * Operation a creer par un import, avec l'identifiant fourni par la banque
 * ({@code null} si le format n'en fournit pas).
 */
public record ImportedTransaction(Transaction transaction, String externalId) {
}

package com.financeapp.core.banksync;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Operation renvoyee par la banque.
 *
 * @param externalId identifiant stable fourni par la banque ({@code null} si absent)
 * @param amount     signe du point de vue du compte (negatif = debit)
 * @param booked     comptabilisee ; {@code false} = en attente
 */
public record RemoteTransaction(String externalId, LocalDate date, BigDecimal amount, String currency,
                                String label, boolean booked) {
}

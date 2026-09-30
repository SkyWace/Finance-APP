package com.financeapp.core.simulation;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Resume d'un credit simule. */
public record LoanPreview(String label, Money principal, Money payment, BigDecimal annualRate, boolean rateEstimated,
                          Money totalInterest, LocalDate endDate) {
}

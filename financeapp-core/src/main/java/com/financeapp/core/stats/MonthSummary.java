package com.financeapp.core.stats;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * Bilan d'un mois (virements internes exclus).
 *
 * @param expenses    total des depenses (negatif)
 * @param saved       revenus + depenses : ce qui reste du mois
 * @param savingsRate part des revenus non depensee, en % ({@code null} sans revenu)
 */
public record MonthSummary(YearMonth month, Money income, Money expenses, Money saved, BigDecimal savingsRate) {
}

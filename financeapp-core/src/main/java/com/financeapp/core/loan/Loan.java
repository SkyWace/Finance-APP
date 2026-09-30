package com.financeapp.core.loan;

import com.financeapp.core.money.Money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Credit amortissable a mensualites constantes.
 *
 * @param annualRate       taux nominal annuel en % (ex. 4,5) ; {@code null} = inconnu, il est alors estime
 *                         a partir de la mensualite
 * @param termMonths       nombre de mensualites
 * @param firstPaymentDate date de la premiere mensualite ; les suivantes tombent le meme jour des mois suivants
 * @param payment          mensualite hors assurance ; {@code null} = calculee a partir du taux
 * @param monthlyInsurance assurance mensuelle (non amortie), zero si aucune
 * @param accountId        compte debite (facultatif)
 * @param recurringId      echeance recurrente liee : c'est elle qui alimente "a venir", le disponible reel
 *                         et les previsions (facultatif)
 */
public record Loan(
        Long id,
        String name,
        Money principal,
        BigDecimal annualRate,
        int termMonths,
        LocalDate firstPaymentDate,
        Money payment,
        Money monthlyInsurance,
        Long accountId,
        Long recurringId,
        Long categoryId,
        boolean archived,
        String note) {

    public static final int MAX_TERM_MONTHS = 600;

    public Loan {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(firstPaymentDate, "firstPaymentDate");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Le nom du crédit est obligatoire");
        }
        name = name.strip();
        if (!principal.isPositive()) {
            throw new IllegalArgumentException("Le capital emprunté doit être strictement positif");
        }
        if (termMonths < 1 || termMonths > MAX_TERM_MONTHS) {
            throw new IllegalArgumentException("La durée doit être comprise entre 1 et " + MAX_TERM_MONTHS + " mois");
        }
        if (annualRate != null && (annualRate.signum() < 0 || annualRate.compareTo(BigDecimal.valueOf(100)) >= 0)) {
            throw new IllegalArgumentException("Le taux annuel doit être compris entre 0 et 100 %");
        }
        if (payment != null && !payment.isPositive()) {
            throw new IllegalArgumentException("La mensualité doit être strictement positive");
        }
        if (annualRate == null && payment == null) {
            throw new IllegalArgumentException("Indiquez le taux ou la mensualité");
        }
        if (payment != null && !payment.isSameCurrency(principal)) {
            throw new IllegalArgumentException("La mensualité doit être dans la devise du capital");
        }
        if (monthlyInsurance == null) {
            monthlyInsurance = Money.zero(principal.currency());
        }
        if (monthlyInsurance.isNegative()) {
            throw new IllegalArgumentException("L'assurance ne peut pas être négative");
        }
    }

    public Loan withId(long newId) {
        return new Loan(newId, name, principal, annualRate, termMonths, firstPaymentDate, payment, monthlyInsurance,
                accountId, recurringId, categoryId, archived, note);
    }

    public Loan withRecurringId(Long value) {
        return new Loan(id, name, principal, annualRate, termMonths, firstPaymentDate, payment, monthlyInsurance,
                accountId, value, categoryId, archived, note);
    }

    public Loan withArchived(boolean value) {
        return new Loan(id, name, principal, annualRate, termMonths, firstPaymentDate, payment, monthlyInsurance,
                accountId, recurringId, categoryId, value, note);
    }
}

package com.financeapp.core.simulation;

import com.financeapp.core.money.Money;

/**
 * Moyennes mensuelles sur la periode simulee (perimetre du disponible).
 *
 * @param income            revenus (recurrents et prevus)
 * @param fixedCharges      charges fixes : depenses recurrentes, mensualites
 * @param oneOffExpenses    depenses ponctuelles prevues (moyennees sur la periode)
 * @param variableSpending  depenses courantes estimees (hors recurrences)
 * @param scheduledSavings  epargne programmee : virements nets vers des comptes hors perimetre
 */
public record MonthlyPicture(Money income, Money fixedCharges, Money oneOffExpenses, Money variableSpending,
                             Money scheduledSavings) {

    /** Reste a vivre : revenus - charges fixes. */
    public Money livingRemainder() {
        return income.minus(fixedCharges);
    }

    /** Disponible mensuel : ce qui reste apres toutes les sorties, epargne programmee comprise. */
    public Money available() {
        return livingRemainder().minus(oneOffExpenses).minus(variableSpending).minus(scheduledSavings);
    }

    /** Capacite d'epargne : disponible + epargne deja programmee. */
    public Money savingCapacity() {
        return available().plus(scheduledSavings);
    }
}

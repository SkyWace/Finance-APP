package com.financeapp.core.recurring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Frequences de recurrence. Les frequences "tous les N ..." utilisent
 * l'intervalle de la regle ; les autres l'ignorent (intervalle = 1).
 */
public enum Frequency {
    WEEKLY("Hebdomadaire", ChronoUnit.WEEKS, 1, false),
    BIWEEKLY("Toutes les deux semaines", ChronoUnit.WEEKS, 2, false),
    MONTHLY("Mensuelle", ChronoUnit.MONTHS, 1, false),
    QUARTERLY("Trimestrielle", ChronoUnit.MONTHS, 3, false),
    YEARLY("Annuelle", ChronoUnit.MONTHS, 12, false),
    EVERY_N_DAYS("Tous les N jours", ChronoUnit.DAYS, 1, true),
    EVERY_N_WEEKS("Toutes les N semaines", ChronoUnit.WEEKS, 1, true),
    EVERY_N_MONTHS("Tous les N mois", ChronoUnit.MONTHS, 1, true);

    private static final BigDecimal DAYS_PER_YEAR = new BigDecimal("365.25");

    private final String label;
    private final ChronoUnit unit;
    private final int baseStep;
    private final boolean custom;

    Frequency(String label, ChronoUnit unit, int baseStep, boolean custom) {
        this.label = label;
        this.unit = unit;
        this.baseStep = baseStep;
        this.custom = custom;
    }

    public String label() {
        return label;
    }

    public boolean isCustom() {
        return custom;
    }

    ChronoUnit unit() {
        return unit;
    }

    /** Pas effectif, dans l'unite de la frequence. */
    long step(int interval) {
        return (long) baseStep * (custom ? interval : 1);
    }

    /**
     * Date de la n-ieme occurrence (n = 0 : date de depart). Toujours calculee
     * depuis la date de depart pour eviter la derive des fins de mois
     * (31 janv. -> 28 fevr. -> 31 mars, et non 28 mars).
     */
    LocalDate nth(LocalDate start, long n, int interval) {
        return start.plus(n * step(interval), unit);
    }

    /**
     * Nombre moyen d'occurrences par an (annee de 365,25 jours), exact pour
     * les frequences mensuelles ; sert aux equivalents mensuels et annuels.
     */
    public BigDecimal occurrencesPerYear(int interval) {
        BigDecimal step = BigDecimal.valueOf(step(interval));
        return switch (unit) {
            case DAYS -> DAYS_PER_YEAR.divide(step, 10, RoundingMode.HALF_EVEN);
            case WEEKS -> DAYS_PER_YEAR.divide(step.multiply(BigDecimal.valueOf(7)), 10, RoundingMode.HALF_EVEN);
            case MONTHS -> BigDecimal.valueOf(12).divide(step, 10, RoundingMode.HALF_EVEN);
            default -> throw new IllegalStateException(unit.toString());
        };
    }

    @Override
    public String toString() {
        return label;
    }
}

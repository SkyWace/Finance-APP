package com.financeapp.core.recurring;

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

    /** Nombre approximatif d'occurrences par an, pour les estimations (couts annuels...). */
    public double occurrencesPerYear(int interval) {
        double perUnit = switch (unit) {
            case DAYS -> 365.25;
            case WEEKS -> 52.1775;
            case MONTHS -> 12.0;
            default -> throw new IllegalStateException(unit.toString());
        };
        return perUnit / step(interval);
    }

    @Override
    public String toString() {
        return label;
    }
}

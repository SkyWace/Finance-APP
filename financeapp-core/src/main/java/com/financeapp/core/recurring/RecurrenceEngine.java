package com.financeapp.core.recurring;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Calcule les occurrences d'une regle recurrente sur une periode, sans rien
 * persister. Sans etat : partageable entre threads.
 */
public final class RecurrenceEngine {

    /** Garde-fou contre une periode demesuree (ex. regle quotidienne sur un siecle). */
    static final int MAX_OCCURRENCES = 10_000;

    /**
     * Occurrences de la regle comprises dans [{@code from}, {@code to}] (bornes incluses).
     * Une regle inactive n'a aucune occurrence.
     */
    public List<LocalDate> occurrences(RecurringRule rule, LocalDate from, LocalDate to) {
        List<LocalDate> result = new ArrayList<>();
        if (!rule.active() || to.isBefore(from)) {
            return result;
        }
        LocalDate effectiveTo = rule.endDate() != null && rule.endDate().isBefore(to) ? rule.endDate() : to;
        if (effectiveTo.isBefore(rule.startDate())) {
            return result;
        }
        long n = firstIndexNotBefore(rule, from);
        while (result.size() < MAX_OCCURRENCES) {
            LocalDate date = rule.frequency().nth(rule.startDate(), n, rule.interval());
            if (date.isAfter(effectiveTo)) {
                break;
            }
            if (!date.isBefore(from)) {
                result.add(date);
            }
            n++;
        }
        return result;
    }

    /** Prochaine occurrence a partir de {@code from} (inclus), dans la limite d'un horizon raisonnable. */
    public Optional<LocalDate> nextOccurrence(RecurringRule rule, LocalDate from) {
        List<LocalDate> next = occurrences(rule, from, from.plusYears(5));
        return next.isEmpty() ? Optional.empty() : Optional.of(next.getFirst());
    }

    /**
     * Indice n a partir duquel iterer : estimation par le bas pour sauter
     * directement pres de {@code from} au lieu de tout reparcourir depuis la
     * date de depart (regle ancienne, fenetre recente).
     */
    private static long firstIndexNotBefore(RecurringRule rule, LocalDate from) {
        if (!from.isAfter(rule.startDate())) {
            return 0;
        }
        Frequency f = rule.frequency();
        long elapsed = f.unit().between(rule.startDate(), from);
        long step = f.step(rule.interval());
        // Pour les mois, le "clamping" des fins de mois peut decaler d'une unite : on recule d'un pas.
        long estimate = elapsed / step - (f.unit() == ChronoUnit.MONTHS ? 1 : 0);
        return Math.max(0, estimate);
    }
}

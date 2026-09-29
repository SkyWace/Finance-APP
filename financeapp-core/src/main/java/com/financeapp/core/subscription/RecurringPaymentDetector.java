package com.financeapp.core.subscription;

import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Repere les paiements reguliers dans l'historique, localement et sans
 * aucune connaissance externe : meme libelle (normalise), intervalle stable
 * (hebdomadaire, bimensuel, mensuel, trimestriel ou annuel) et montant
 * stable (ecart max/min ≤ 15 %). Les courses ou le carburant, aux montants
 * variables, ne sont donc pas proposes.
 */
public final class RecurringPaymentDetector {

    static final BigDecimal MAX_AMOUNT_SPREAD = new BigDecimal("1.15");

    private record Pattern(Frequency frequency, int minDays, int maxDays, int minOccurrences) {
    }

    private static final List<Pattern> PATTERNS = List.of(
            new Pattern(Frequency.WEEKLY, 6, 8, 4),
            new Pattern(Frequency.BIWEEKLY, 13, 16, 3),
            new Pattern(Frequency.MONTHLY, 27, 33, 3),
            new Pattern(Frequency.QUARTERLY, 85, 97, 3),
            new Pattern(Frequency.YEARLY, 355, 375, 2));

    /**
     * @param history  operations comptees dans le solde (quelques mois d'historique)
     * @param existing regles recurrentes deja enregistrees (leurs libelles sont exclus)
     */
    public List<RecurringPaymentCandidate> detect(List<Transaction> history, List<RecurringRule> existing, LocalDate today) {
        Set<String> known = existing.stream().map(r -> normalize(r.label())).collect(Collectors.toSet());
        Map<String, List<Transaction>> groups = history.stream()
                .filter(t -> t.type() == TransactionType.EXPENSE && t.recurringId() == null)
                .collect(Collectors.groupingBy(t -> normalize(t.label()) + "|" + t.accountId()));

        List<RecurringPaymentCandidate> result = new ArrayList<>();
        for (List<Transaction> group : groups.values()) {
            String key = normalize(group.getFirst().label());
            if (key.isEmpty() || known.contains(key)) {
                continue;
            }
            List<Transaction> sorted = group.stream().sorted(Comparator.comparing(Transaction::date)).toList();
            Pattern pattern = match(sorted);
            if (pattern == null || !stableAmounts(sorted)) {
                continue;
            }
            Transaction last = sorted.getLast();
            LocalDate next = nextDate(pattern.frequency(), last.date());
            // Paiement interrompu : plus d'une echeance manquee.
            if (nextDate(pattern.frequency(), next).isBefore(today)) {
                continue;
            }
            Money typical = median(sorted);
            result.add(new RecurringPaymentCandidate(last.label(), typical, pattern.frequency(), sorted.size(),
                    last.date(), next, last.accountId(), last.categoryId()));
        }
        result.sort(Comparator.comparing((RecurringPaymentCandidate c) -> c.monthlyCost().amount()).reversed());
        return result;
    }

    /** Libelle compare sans casse, accents, chiffres ni ponctuation ("NETFLIX.COM 12/09" = "netflix com"). */
    public static String normalize(String label) {
        String s = Normalizer.normalize(label.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z ]", " ")
                .replaceAll("\\b(prlv|sepa|prelevement|cb|carte|paiement|par|facture)\\b", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return s;
    }

    private static Pattern match(List<Transaction> sorted) {
        if (sorted.size() < 2) {
            return null;
        }
        for (Pattern p : PATTERNS) {
            if (sorted.size() < p.minOccurrences()) {
                continue;
            }
            boolean all = true;
            for (int i = 1; i < sorted.size() && all; i++) {
                long gap = ChronoUnit.DAYS.between(sorted.get(i - 1).date(), sorted.get(i).date());
                all = gap >= p.minDays() && gap <= p.maxDays();
            }
            if (all) {
                return p;
            }
        }
        return null;
    }

    private static boolean stableAmounts(List<Transaction> sorted) {
        BigDecimal min = sorted.stream().map(t -> t.amount().abs().amount()).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal max = sorted.stream().map(t -> t.amount().abs().amount()).max(BigDecimal::compareTo).orElseThrow();
        return max.compareTo(min.multiply(MAX_AMOUNT_SPREAD)) <= 0;
    }

    private static Money median(List<Transaction> sorted) {
        List<Money> amounts = sorted.stream().map(t -> t.amount().abs()).sorted().toList();
        return amounts.get(amounts.size() / 2);
    }

    private static LocalDate nextDate(Frequency f, LocalDate last) {
        return switch (f) {
            case WEEKLY -> last.plusWeeks(1);
            case BIWEEKLY -> last.plusWeeks(2);
            case QUARTERLY -> last.plusMonths(3);
            case YEARLY -> last.plusYears(1);
            default -> last.plusMonths(1);
        };
    }
}

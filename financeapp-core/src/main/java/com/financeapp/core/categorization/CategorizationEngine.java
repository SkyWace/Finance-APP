package com.financeapp.core.categorization;

import com.financeapp.core.text.LabelNormalizer;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Categorisation automatique, 100 % locale.
 *
 * <ol>
 *   <li>Regles explicites : le motif le plus long (le plus specifique) l'emporte
 *       ("carrefour drive" avant "carrefour") ; a egalite, la regle la plus ancienne.</li>
 *   <li>A defaut, historique : si un meme libelle normalise a ete classe au moins
 *       2 fois, dont au moins 75 % dans la meme categorie, cette categorie est proposee.</li>
 * </ol>
 */
public final class CategorizationEngine {

    static final int HISTORY_MIN_COUNT = 2;
    static final double HISTORY_MIN_SHARE = 0.75;

    private final List<CategorizationRule> rules;
    private final Map<String, Map<Long, Integer>> history = new HashMap<>();

    /** @param categorizedHistory operations deja categorisees servant a l'apprentissage */
    public CategorizationEngine(List<CategorizationRule> rules, List<Transaction> categorizedHistory) {
        this.rules = rules.stream()
                .filter(CategorizationRule::active)
                .sorted(Comparator.comparingInt((CategorizationRule r) -> r.normalizedPattern().length()).reversed()
                        .thenComparing(r -> r.id() == null ? Long.MAX_VALUE : r.id()))
                .toList();
        for (Transaction t : categorizedHistory) {
            if (t.categoryId() != null && !t.isTransfer()) {
                history.computeIfAbsent(key(t.label(), t.type()), k -> new HashMap<>())
                        .merge(t.categoryId(), 1, Integer::sum);
            }
        }
    }

    public Optional<CategorySuggestion> suggest(String label, TransactionType type) {
        if (type == TransactionType.TRANSFER) {
            return Optional.empty();
        }
        Optional<CategorizationRule> rule = matchingRule(label, type);
        if (rule.isPresent()) {
            return rule.map(r -> new CategorySuggestion(r.categoryId(), CategorySuggestion.Source.RULE, r));
        }
        Map<Long, Integer> counts = history.get(key(label, type));
        if (counts == null) {
            return Optional.empty();
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .filter(e -> e.getValue() >= HISTORY_MIN_COUNT && e.getValue() >= HISTORY_MIN_SHARE * total)
                .map(e -> new CategorySuggestion(e.getKey(), CategorySuggestion.Source.HISTORY, null));
    }

    public Optional<CategorizationRule> matchingRule(String label, TransactionType type) {
        String normalized = LabelNormalizer.normalize(label);
        return rules.stream()
                .filter(r -> r.appliesTo() == null || r.appliesTo() == type)
                .filter(r -> LabelNormalizer.containsWords(normalized, r.normalizedPattern()))
                .findFirst();
    }

    private static String key(String label, TransactionType type) {
        return type + "|" + LabelNormalizer.normalize(label);
    }
}

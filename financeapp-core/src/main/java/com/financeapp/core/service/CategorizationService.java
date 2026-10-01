package com.financeapp.core.service;

import com.financeapp.core.categorization.CategorizationEngine;
import com.financeapp.core.categorization.CategorizationRule;
import com.financeapp.core.categorization.CategorySuggestion;
import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.port.CategorizationRuleRepository;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.text.LabelNormalizer;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Regles de categorisation automatique et suggestions (100 % locales). */
public final class CategorizationService {

    private static final LocalDate FAR_FUTURE = LocalDate.of(9999, 12, 31);
    private static final LocalDate FAR_PAST = LocalDate.of(1900, 1, 1);

    private final CategorizationRuleRepository rules;
    private final TransactionRepository transactions;
    private final CategoryService categories;
    private final PlanningService planning;

    public CategorizationService(CategorizationRuleRepository rules, TransactionRepository transactions,
                                 CategoryService categories, PlanningService planning) {
        this.rules = rules;
        this.transactions = transactions;
        this.categories = categories;
        this.planning = planning;
    }

    public List<CategorizationRule> findAll() {
        return rules.findAll();
    }

    public CategorizationRule save(CategorizationRule rule) {
        Category category = categories.find(rule.categoryId())
                .orElseThrow(() -> new BusinessException("Catégorie introuvable"));
        if (rule.appliesTo() == TransactionType.EXPENSE && category.kind() == CategoryKind.INCOME
                || rule.appliesTo() == TransactionType.INCOME && category.kind() == CategoryKind.EXPENSE) {
            throw new BusinessException("La catégorie ne correspond pas au type d'opération de la règle");
        }
        boolean duplicate = rules.findAll().stream()
                .anyMatch(r -> !Objects.equals(r.id(), rule.id())
                        && r.normalizedPattern().equals(rule.normalizedPattern())
                        && Objects.equals(r.appliesTo(), rule.appliesTo()));
        if (duplicate) {
            throw new BusinessException("Une règle existe déjà pour « " + rule.pattern() + " »");
        }
        return rules.save(rule);
    }

    public void delete(long id) {
        rules.delete(id);
    }

    /** Moteur construit sur les regles actuelles et 12 mois d'operations categorisees. */
    public CategorizationEngine engine() {
        LocalDate today = planning.today();
        List<Transaction> history = transactions.findCounted(today.minusMonths(12), today).stream()
                .filter(t -> t.categoryId() != null)
                .toList();
        return new CategorizationEngine(rules.findAll(), history);
    }

    public Optional<CategorySuggestion> suggest(String label, TransactionType type) {
        return engine().suggest(label, type);
    }

    /**
     * Apres une correction manuelle : motif propose pour une nouvelle regle
     * ("Toujours classer les operations contenant CARREFOUR dans Courses ?"),
     * ou vide si une regle donne deja ce resultat.
     */
    public Optional<String> ruleProposal(String label, TransactionType type, long categoryId) {
        if (type == TransactionType.TRANSFER) {
            return Optional.empty();
        }
        Optional<CategorizationRule> current = engine().matchingRule(label, type);
        if (current.isPresent() && current.get().categoryId() == categoryId) {
            return Optional.empty();
        }
        String keyword = LabelNormalizer.keyword(label);
        return keyword.isBlank() ? Optional.empty() : Optional.of(keyword.toUpperCase());
    }

    /**
     * Applique les regles aux operations sans categorie (effectuees, en attente
     * ou prevues). Les operations deja categorisees ne sont jamais modifiees.
     *
     * @return nombre d'operations categorisees
     */
    public int applyToUncategorized() {
        CategorizationEngine engine = new CategorizationEngine(rules.findAll(), List.of());
        List<Transaction> candidates = new ArrayList<>(transactions.findCounted(FAR_PAST, FAR_FUTURE));
        candidates.addAll(transactions.findPlannedUntil(FAR_FUTURE));
        int count = 0;
        for (Transaction t : candidates) {
            if (t.categoryId() != null || t.isTransfer() || t.isSplit()) { // une ventilation n'est jamais ecrasee
                continue;
            }
            Optional<CategorizationRule> rule = engine.matchingRule(t.label(), t.type());
            if (rule.isPresent()) {
                transactions.update(withCategory(t, rule.get().categoryId()));
                count++;
            }
        }
        return count;
    }

    static Transaction withCategory(Transaction t, Long categoryId) {
        return t.withCategory(categoryId);
    }
}

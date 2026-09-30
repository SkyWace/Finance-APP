package com.financeapp.core.categorization;

import com.financeapp.core.text.LabelNormalizer;
import com.financeapp.core.transaction.TransactionType;

/**
 * Regle locale : "si le libelle contient {@code pattern}, alors {@code categoryId}".
 * La comparaison se fait sur les libelles normalises (sans casse, accents ni chiffres),
 * par mots entiers.
 *
 * @param appliesTo {@code null} = depenses et revenus ; sinon uniquement ce type d'operation
 */
public record CategorizationRule(Long id, String pattern, long categoryId, TransactionType appliesTo, boolean active) {

    public CategorizationRule {
        if (pattern == null || LabelNormalizer.normalize(pattern).isBlank()) {
            throw new IllegalArgumentException("Le motif doit contenir au moins un mot (lettres)");
        }
        pattern = pattern.strip();
        if (appliesTo == TransactionType.TRANSFER) {
            throw new IllegalArgumentException("Les virements internes ne sont pas catégorisés");
        }
    }

    public String normalizedPattern() {
        return LabelNormalizer.normalize(pattern);
    }

    public CategorizationRule withId(long newId) {
        return new CategorizationRule(newId, pattern, categoryId, appliesTo, active);
    }
}

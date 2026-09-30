package com.financeapp.core.categorization;

/**
 * Categorie proposee pour une operation, avec son origine (pour l'expliquer a l'utilisateur).
 *
 * @param rule regle appliquee ({@code null} si la suggestion vient de l'historique)
 */
public record CategorySuggestion(long categoryId, Source source, CategorizationRule rule) {

    public enum Source {
        RULE("règle"),
        HISTORY("vos opérations passées"),
        PLANNED("l'opération prévue");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}

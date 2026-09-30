package com.financeapp.core.imports;

import com.financeapp.core.categorization.CategorySuggestion;
import com.financeapp.core.planning.PlannedItem;
import com.financeapp.core.transaction.Transaction;

/**
 * Ligne analysee, prete a etre presentee dans l'apercu d'import.
 *
 * @param matchedExisting operation deja presente (doublon certain ou possible)
 * @param matchedPlanned  operation prevue ou echeance recurrente que cette ligne realise
 * @param suggestion      categorie proposee ({@code null} si aucune)
 * @param includedByDefault coche dans l'apercu : jamais pour un doublon, meme possible
 */
public record ImportCandidate(
        ImportedRow row,
        Kind kind,
        Transaction matchedExisting,
        PlannedItem matchedPlanned,
        CategorySuggestion suggestion,
        boolean includedByDefault) {

    public enum Kind {
        NEW("Nouvelle"),
        MATCHES_PLANNED("Réalise une opération prévue"),
        MATCHES_RECURRING("Réalise une échéance récurrente"),
        DUPLICATE("Déjà présente"),
        POSSIBLE_DUPLICATE("Doublon possible"),
        DUPLICATE_IN_FILE("En double dans le fichier"),
        INVALID("Illisible");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Cas ambigus : exclus par defaut, a confirmer explicitement. */
        public boolean needsDecision() {
            return this == DUPLICATE || this == POSSIBLE_DUPLICATE || this == DUPLICATE_IN_FILE;
        }
    }
}

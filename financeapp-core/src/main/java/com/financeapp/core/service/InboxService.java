package com.financeapp.core.service;

import com.financeapp.core.categorization.CategorizationEngine;
import com.financeapp.core.categorization.CategorySuggestion;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Operations importees "a valider" : l'utilisateur confirme ou corrige la
 * categorie proposee. Elles comptent deja dans le solde (ce sont des
 * operations reelles) ; seule leur categorisation reste a confirmer.
 */
public final class InboxService {

    /**
     * @param suggestion categorie proposee si l'operation n'en a pas encore ({@code null} sinon ou si aucune)
     */
    public record InboxItem(Transaction transaction, CategorySuggestion suggestion) {

        /** Categorie a valider : celle de l'operation, sinon la suggestion. */
        public Long proposedCategoryId() {
            return transaction.categoryId() != null ? transaction.categoryId()
                    : suggestion == null ? null : suggestion.categoryId();
        }
    }

    private final TransactionRepository transactions;
    private final CategorizationService categorization;

    public InboxService(TransactionRepository transactions, CategorizationService categorization) {
        this.transactions = transactions;
        this.categorization = categorization;
    }

    public long count() {
        return transactions.countNeedingReview();
    }

    public List<InboxItem> items() {
        CategorizationEngine engine = categorization.engine();
        List<InboxItem> items = new ArrayList<>();
        for (Transaction t : transactions.findNeedingReview()) {
            boolean categorized = t.categoryId() != null || t.isSplit();
            items.add(new InboxItem(t, categorized ? null : engine.suggest(t.label(), t.type()).orElse(null)));
        }
        return items;
    }

    /**
     * Valide l'operation avec la categorie choisie ({@code null} = sans categorie).
     * Une operation deja ventilee est validee telle quelle : sa ventilation n'est jamais ecrasee.
     */
    public void validate(long transactionId, Long categoryId) {
        Transaction t = transactions.findById(transactionId)
                .orElseThrow(() -> new BusinessException("Opération introuvable"));
        if (!Objects.equals(t.categoryId(), categoryId) && !t.isTransfer() && !t.isSplit()) {
            transactions.update(CategorizationService.withCategory(t, categoryId));
        }
        transactions.markReviewed(transactionId);
    }

    /** Valide d'un coup toutes les operations qui ont une categorie (ou une suggestion). */
    public int validateAllWithCategory() {
        int count = 0;
        for (InboxItem item : items()) {
            if (item.proposedCategoryId() != null) {
                validate(item.transaction().id(), item.proposedCategoryId());
                count++;
            }
        }
        return count;
    }
}

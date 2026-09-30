package com.financeapp.desktop.ui.dialogs;

import com.financeapp.core.categorization.CategorizationRule;
import com.financeapp.core.transaction.TransactionType;
import com.financeapp.desktop.ui.common.UiContext;

/**
 * Apres une correction manuelle de categorie, propose de creer la regle
 * correspondante ("Toujours classer les operations contenant CARREFOUR dans
 * Courses ?"). L'utilisateur peut ajuster le motif ou refuser.
 */
public final class RuleProposal {

    private RuleProposal() {
    }

    /** @return {@code true} si une regle a ete creee */
    public static boolean offer(UiContext ctx, String label, TransactionType type, Long categoryId) {
        if (categoryId == null) {
            return false;
        }
        var proposal = ctx.services().categorization().ruleProposal(label, type, categoryId);
        if (proposal.isEmpty()) {
            return false;
        }
        String category = ctx.services().categories().fullName(categoryId);
        boolean accepted = com.financeapp.desktop.ui.common.Dialogs.confirm(ctx.window(), "Créer une règle ?",
                "Toujours classer les opérations contenant « " + proposal.get() + " » dans « " + category + " » ?\n\n"
                        + "Vous pourrez ajuster le motif à l'étape suivante.", "Créer la règle…");
        if (!accepted) {
            return false;
        }
        CategorizationRule template = new CategorizationRule(null, proposal.get(), categoryId,
                type == TransactionType.TRANSFER ? null : type, true);
        return new RuleDialog(ctx, template).showAndWait().isPresent();
    }
}

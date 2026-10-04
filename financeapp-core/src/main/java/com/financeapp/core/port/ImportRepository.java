package com.financeapp.core.port;

import com.financeapp.core.imports.ImportBatch;
import com.financeapp.core.imports.ImportedTransaction;
import com.financeapp.core.imports.Reconciliation;

import java.util.List;
import java.util.Set;

public interface ImportRepository {

    /**
     * Enregistre un import de maniere atomique : lot, nouvelles operations (marquees
     * "a valider", avec leur identifiant bancaire eventuel) et rapprochements.
     *
     * @return le lot enregistre (avec son identifiant)
     */
    default ImportBatch commit(ImportBatch batch, List<ImportedTransaction> created, List<Reconciliation> reconciliations) {
        return commit(batch, created, reconciliations, true);
    }

    /**
     * @param review marquer les operations creees "a valider" (Inbox) ; faux pour des operations
     *               deja saisies par l'utilisateur lui-meme (version web)
     */
    ImportBatch commit(ImportBatch batch, List<ImportedTransaction> created, List<Reconciliation> reconciliations,
                       boolean review);

    List<ImportBatch> findAll();

    /** Annule un import, atomiquement : supprime les operations creees, restaure les operations rapprochees. */
    void undo(long batchId);

    Set<String> externalIds(long accountId);
}

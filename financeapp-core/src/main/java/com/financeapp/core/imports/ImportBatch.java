package com.financeapp.core.imports;

import java.time.Instant;

/**
 * Trace d'un import : permet de l'annuler (suppression des operations creees,
 * retour a l'etat "prevu" des operations rapprochees).
 *
 * @param created    operations creees
 * @param reconciled operations prevues realisees par l'import
 * @param skipped    lignes non importees (doublons, lignes illisibles, lignes decochees)
 */
public record ImportBatch(Long id, long accountId, String fileName, String format, Instant importedAt,
                          int created, int reconciled, int skipped, boolean undone) {

    public ImportBatch withId(long newId) {
        return new ImportBatch(newId, accountId, fileName, format, importedAt, created, reconciled, skipped, undone);
    }
}

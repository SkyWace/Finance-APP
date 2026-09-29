package com.financeapp.infra.backup;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Description d'un fichier de sauvegarde verifie.
 *
 * @param schemaVersion version de schema Flyway contenue dans la sauvegarde
 */
public record BackupInfo(Path file, Instant modifiedAt, long sizeBytes, String schemaVersion,
                         long accounts, long transactions, boolean automatic) {
}

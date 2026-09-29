package com.financeapp.infra.backup;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Description d'un fichier de sauvegarde.
 *
 * @param schemaVersion version de schema Flyway contenue dans la sauvegarde ({@code null} si illisible sans mot de passe)
 * @param sameKey       chiffree avec la cle de cette installation : restaurable sans mot de passe ;
 *                      sinon, le mot de passe associe a son fichier {@code .key} est requis
 *                      (et {@code accounts}/{@code transactions} valent -1)
 */
public record BackupInfo(Path file, Instant modifiedAt, long sizeBytes, String schemaVersion,
                         long accounts, long transactions, boolean automatic, boolean sameKey) {
}

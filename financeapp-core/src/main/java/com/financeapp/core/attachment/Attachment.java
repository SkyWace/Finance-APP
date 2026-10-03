package com.financeapp.core.attachment;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Justificatif joint a une operation (facture, ticket, bulletin...). Seules ces
 * informations sont lues pour les listes ; le contenu est lu a la demande.
 *
 * @param size taille du contenu, en octets
 */
public record Attachment(Long id, long transactionId, String fileName, AttachmentType type, long size,
                         LocalDateTime addedAt) {

    public Attachment {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(addedAt, "addedAt");
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Le nom du fichier est obligatoire");
        }
    }
}

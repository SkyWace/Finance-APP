package com.financeapp.core.service;

import com.financeapp.core.attachment.Attachment;
import com.financeapp.core.attachment.AttachmentType;
import com.financeapp.core.port.AttachmentRepository;
import com.financeapp.core.port.TransactionRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Justificatifs joints aux operations : factures, tickets, bulletins... Le contenu est
 * stocke dans la base chiffree du profil : il est donc chiffre, sauvegarde et restaure
 * avec elle, et supprime avec l'operation.
 */
public final class AttachmentService {

    /** Taille maximale d'un justificatif : 10 Mo. */
    public static final long MAX_SIZE = 10L * 1024 * 1024;
    /** Nombre maximal de justificatifs par operation. */
    public static final int MAX_PER_TRANSACTION = 20;
    static final int MAX_NAME_LENGTH = 120;

    /** Nombre de justificatifs et taille cumulee. */
    public record Usage(long count, long bytes) {
    }

    private final AttachmentRepository attachments;
    private final TransactionRepository transactions;
    private final Clock clock;

    public AttachmentService(AttachmentRepository attachments, TransactionRepository transactions, Clock clock) {
        this.attachments = attachments;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Verifie un fichier avant de le joindre (format, taille) ; renvoie son format. */
    public static AttachmentType check(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException("Le fichier est vide");
        }
        if (content.length > MAX_SIZE) {
            throw new BusinessException("Le fichier dépasse 10 Mo : réduisez-le (photo moins lourde, PDF compressé) "
                    + "avant de le joindre");
        }
        return AttachmentType.detect(content).orElseThrow(() -> new BusinessException(
                "Format non pris en charge : seuls les PDF et les images (JPEG, PNG, GIF, WebP, HEIC) sont acceptés"));
    }

    public Attachment attach(long transactionId, String fileName, byte[] content) {
        if (transactions.findById(transactionId).isEmpty()) {
            throw new BusinessException("Opération introuvable");
        }
        AttachmentType type = check(content);
        if (attachments.findByTransaction(transactionId).size() >= MAX_PER_TRANSACTION) {
            throw new BusinessException("Une opération ne peut pas avoir plus de " + MAX_PER_TRANSACTION + " justificatifs");
        }
        return attachments.insert(new Attachment(null, transactionId, cleanName(fileName, type), type,
                content.length, LocalDateTime.now(clock).withNano(0)), content.clone());
    }

    public List<Attachment> list(long transactionId) {
        return attachments.findByTransaction(transactionId);
    }

    public byte[] content(long attachmentId) {
        return attachments.content(attachmentId).orElseThrow(() -> new BusinessException("Justificatif introuvable"));
    }

    public void delete(long attachmentId) {
        attachments.delete(attachmentId);
    }

    public Map<Long, Integer> counts(Collection<Long> transactionIds) {
        return transactionIds.isEmpty() ? Map.of() : attachments.countByTransactions(transactionIds);
    }

    /** Justificatifs qui disparaitraient en defaisant cet import (ils sont joints a ses operations). */
    public long countInImport(long batchId) {
        return attachments.countInImportBatch(batchId);
    }

    public Usage usage() {
        long[] u = attachments.usage();
        return new Usage(u[0], u[1]);
    }

    /**
     * Nom affiche : sans dossier, sans caracteres de controle ni caracteres interdits par
     * Windows, raccourci, avec l'extension du format reel.
     */
    static String cleanName(String fileName, AttachmentType type) {
        String name = fileName == null ? "" : fileName;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[\\p{Cntrl}<>:\"|?*]", "").strip();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot).strip() : name;
        if (base.isEmpty() || base.chars().allMatch(c -> c == '.')) {
            base = "justificatif";
        }
        String extension = "." + type.extension();
        if (base.length() + extension.length() > MAX_NAME_LENGTH) {
            base = base.substring(0, MAX_NAME_LENGTH - extension.length()).strip();
        }
        return base + extension;
    }
}

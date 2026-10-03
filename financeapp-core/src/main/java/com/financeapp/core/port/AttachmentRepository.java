package com.financeapp.core.port;

import com.financeapp.core.attachment.Attachment;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Justificatifs des operations. Les listes ne lisent jamais le contenu. */
public interface AttachmentRepository {

    Attachment insert(Attachment attachment, byte[] content);

    List<Attachment> findByTransaction(long transactionId);

    Optional<Attachment> findById(long id);

    Optional<byte[]> content(long id);

    void delete(long id);

    /** Nombre de justificatifs par operation (seulement celles qui en ont). */
    Map<Long, Integer> countByTransactions(Collection<Long> transactionIds);

    /** Justificatifs des operations creees par un import (supprimes si l'import est defait). */
    long countInImportBatch(long batchId);

    /** Nombre total de justificatifs et taille cumulee, en octets. */
    long[] usage();
}

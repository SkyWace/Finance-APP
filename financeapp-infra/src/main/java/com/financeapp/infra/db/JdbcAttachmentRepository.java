package com.financeapp.infra.db;

import com.financeapp.core.attachment.Attachment;
import com.financeapp.core.attachment.AttachmentType;
import com.financeapp.core.port.AttachmentRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Justificatifs : les listes ne lisent que les metadonnees, le contenu est lu a la demande. */
public final class JdbcAttachmentRepository implements AttachmentRepository {

    private static final String COLUMNS = "id, transaction_id, file_name, media_type, size_bytes, added_at";

    private static final RowMapper<Attachment> MAPPER = (rs, i) -> new Attachment(
            rs.getLong("id"), rs.getLong("transaction_id"), rs.getString("file_name"),
            type(rs.getString("media_type")), rs.getLong("size_bytes"), LocalDateTime.parse(rs.getString("added_at")));

    private final JdbcClient jdbc;

    public JdbcAttachmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static AttachmentType type(String mediaType) {
        return Arrays.stream(AttachmentType.values()).filter(t -> t.mediaType().equals(mediaType)).findFirst()
                .orElse(AttachmentType.PDF);
    }

    @Override
    public Attachment insert(Attachment a, byte[] content) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO attachments (transaction_id, file_name, media_type, size_bytes, added_at, content)
                        VALUES (:tx, :name, :type, :size, :added, :content)
                        """)
                .param("tx", a.transactionId()).param("name", a.fileName()).param("type", a.type().mediaType())
                .param("size", a.size()).param("added", a.addedAt().toString()).param("content", content)
                .update(keys);
        return new Attachment(JdbcKeys.id(keys), a.transactionId(), a.fileName(), a.type(), a.size(), a.addedAt());
    }

    @Override
    public List<Attachment> findByTransaction(long transactionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM attachments WHERE transaction_id = :tx ORDER BY id")
                .param("tx", transactionId).query(MAPPER).list();
    }

    @Override
    public Optional<Attachment> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM attachments WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public Optional<byte[]> content(long id) {
        return jdbc.sql("SELECT content FROM attachments WHERE id = :id").param("id", id)
                .query((rs, i) -> rs.getBytes("content")).optional();
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM attachments WHERE id = :id").param("id", id).update();
    }

    @Override
    public Map<Long, Integer> countByTransactions(Collection<Long> transactionIds) {
        Map<Long, Integer> counts = new HashMap<>();
        List<Long> ids = List.copyOf(transactionIds);
        // Par paquets : nombre de parametres limite dans SQLite.
        for (int from = 0; from < ids.size(); from += 500) {
            jdbc.sql("SELECT transaction_id, count(*) AS n FROM attachments WHERE transaction_id IN (:ids) "
                            + "GROUP BY transaction_id")
                    .param("ids", ids.subList(from, Math.min(ids.size(), from + 500)))
                    .query(rs -> {
                        counts.put(rs.getLong("transaction_id"), rs.getInt("n"));
                    });
        }
        return counts;
    }

    @Override
    public long countInImportBatch(long batchId) {
        return jdbc.sql("SELECT count(*) FROM attachments a JOIN transactions t ON t.id = a.transaction_id "
                + "WHERE t.import_batch_id = :batch").param("batch", batchId).query(Long.class).single();
    }

    @Override
    public long[] usage() {
        return jdbc.sql("SELECT count(*) AS n, coalesce(sum(size_bytes), 0) AS bytes FROM attachments")
                .query((rs, i) -> new long[]{rs.getLong("n"), rs.getLong("bytes")}).single();
    }
}

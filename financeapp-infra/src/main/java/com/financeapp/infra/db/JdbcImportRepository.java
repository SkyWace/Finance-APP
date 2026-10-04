package com.financeapp.infra.db;

import com.financeapp.core.imports.ImportBatch;
import com.financeapp.core.imports.ImportedTransaction;
import com.financeapp.core.imports.Reconciliation;
import com.financeapp.core.port.ImportRepository;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.transaction.Transaction;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Imports : tout est fait dans une transaction SQL unique (tout ou rien), y compris l'annulation. */
public final class JdbcImportRepository implements ImportRepository {

    private static final RowMapper<ImportBatch> MAPPER = (rs, i) -> new ImportBatch(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getString("file_name"),
            rs.getString("format"),
            Instant.parse(rs.getString("imported_at")),
            rs.getInt("created"),
            rs.getInt("reconciled"),
            rs.getInt("skipped"),
            DbCodec.bool(rs, "undone"));

    private final JdbcClient jdbc;
    private final TransactionRepository transactions;
    private final TransactionTemplate tx;

    public JdbcImportRepository(JdbcClient jdbc, TransactionRepository transactions, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.tx = tx;
    }

    @Override
    public ImportBatch commit(ImportBatch batch, List<ImportedTransaction> created, List<Reconciliation> reconciliations,
                              boolean review) {
        return tx.execute(status -> {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO import_batches (account_id, file_name, format, imported_at, created, reconciled, skipped)
                    VALUES (:account, :file, :format, :at, :created, :reconciled, :skipped)
                    """)
                    .param("account", batch.accountId())
                    .param("file", batch.fileName())
                    .param("format", batch.format())
                    .param("at", batch.importedAt().toString())
                    .param("created", batch.created())
                    .param("reconciled", batch.reconciled())
                    .param("skipped", batch.skipped())
                    .update(keys);
            long batchId = JdbcKeys.id(keys);
            for (ImportedTransaction it : created) {
                Transaction t = transactions.insert(it.transaction());
                jdbc.sql("UPDATE transactions SET import_batch_id = :batch, external_id = :ext, needs_review = :review WHERE id = :id")
                        .param("batch", batchId).param("ext", it.externalId()).param("review", review ? 1 : 0)
                        .param("id", t.id()).update();
            }
            for (Reconciliation r : reconciliations) {
                jdbc.sql("""
                        INSERT INTO import_reconciliations (batch_id, transaction_id, previous_status, previous_date, previous_label)
                        VALUES (:batch, :tx, :status, :date, :label)
                        """)
                        .param("batch", batchId).param("tx", r.transactionId())
                        .param("status", r.previousStatus().name())
                        .param("date", DbCodec.date(r.previousDate()))
                        .param("label", r.previousLabel())
                        .update();
                jdbc.sql("UPDATE transactions SET status = 'COMPLETED', date = :date, label = :label, updated_at = :now WHERE id = :id")
                        .param("date", DbCodec.date(r.newDate())).param("label", r.newLabel())
                        .param("now", DbCodec.now()).param("id", r.transactionId())
                        .update();
            }
            return batch.withId(batchId);
        });
    }

    @Override
    public List<ImportBatch> findAll() {
        return jdbc.sql("SELECT * FROM import_batches ORDER BY imported_at DESC, id DESC").query(MAPPER).list();
    }

    @Override
    public void undo(long batchId) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("""
                    UPDATE transactions SET
                        status = (SELECT previous_status FROM import_reconciliations r WHERE r.batch_id = :batch AND r.transaction_id = transactions.id),
                        date = (SELECT previous_date FROM import_reconciliations r WHERE r.batch_id = :batch AND r.transaction_id = transactions.id),
                        label = (SELECT previous_label FROM import_reconciliations r WHERE r.batch_id = :batch AND r.transaction_id = transactions.id),
                        updated_at = :now
                    WHERE id IN (SELECT transaction_id FROM import_reconciliations WHERE batch_id = :batch)
                    """).param("batch", batchId).param("now", DbCodec.now()).update();
            jdbc.sql("DELETE FROM transactions WHERE import_batch_id = :batch").param("batch", batchId).update();
            jdbc.sql("UPDATE import_batches SET undone = 1 WHERE id = :batch").param("batch", batchId).update();
        });
    }

    @Override
    public Set<String> externalIds(long accountId) {
        return new HashSet<>(jdbc.sql("SELECT external_id FROM transactions WHERE account_id = :a AND external_id IS NOT NULL")
                .param("a", accountId).query(String.class).list());
    }
}

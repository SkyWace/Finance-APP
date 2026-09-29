package com.financeapp.infra.db;

import com.financeapp.core.port.OccurrenceKey;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class JdbcTransactionRepository implements TransactionRepository {

    private static final String SELECT = """
            SELECT t.*, a.currency FROM transactions t JOIN accounts a ON a.id = t.account_id
            """;
    private static final String COUNTED = "t.status IN ('PENDING','COMPLETED')";

    private static final RowMapper<Transaction> MAPPER = (rs, i) -> new Transaction(
            rs.getLong("id"),
            rs.getLong("account_id"),
            DbCodec.date(rs, "date"),
            rs.getString("label"),
            DbCodec.money(rs, "amount_minor", Currency.getInstance(rs.getString("currency"))),
            TransactionType.valueOf(rs.getString("type")),
            TransactionStatus.valueOf(rs.getString("status")),
            DbCodec.nullableLong(rs, "category_id"),
            rs.getString("note"),
            rs.getString("transfer_group"),
            DbCodec.nullableLong(rs, "transfer_account_id"),
            DbCodec.nullableLong(rs, "recurring_id"),
            DbCodec.date(rs, "occurrence_date"));

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcTransactionRepository(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Transaction insert(Transaction t) {
        String now = DbCodec.now();
        KeyHolder keys = new GeneratedKeyHolder();
        bind(jdbc.sql("""
                INSERT INTO transactions (account_id, date, label, amount_minor, type, status, category_id, note,
                       transfer_group, transfer_account_id, recurring_id, occurrence_date, created_at, updated_at)
                VALUES (:account, :date, :label, :amount, :type, :status, :category, :note,
                        :transferGroup, :transferAccount, :recurring, :occurrence, :now, :now)
                """), t).param("now", now).update(keys);
        return t.withId(JdbcKeys.id(keys));
    }

    @Override
    public List<Transaction> insertAll(List<Transaction> transactions) {
        return tx.execute(status -> transactions.stream().map(this::insert).toList());
    }

    @Override
    public Transaction update(Transaction t) {
        int rows = bind(jdbc.sql("""
                UPDATE transactions SET account_id = :account, date = :date, label = :label, amount_minor = :amount,
                       type = :type, status = :status, category_id = :category, note = :note,
                       transfer_group = :transferGroup, transfer_account_id = :transferAccount,
                       recurring_id = :recurring, occurrence_date = :occurrence, updated_at = :now
                WHERE id = :id
                """), t).param("id", t.id()).param("now", DbCodec.now()).update();
        if (rows != 1) {
            throw new IllegalStateException("Operation introuvable : " + t.id());
        }
        return t;
    }

    @Override
    public void updateAll(List<Transaction> transactions) {
        tx.executeWithoutResult(status -> transactions.forEach(this::update));
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Transaction t) {
        return spec
                .param("account", t.accountId())
                .param("date", DbCodec.date(t.date()))
                .param("label", t.label())
                .param("amount", t.amount().toMinorUnits())
                .param("type", t.type().name())
                .param("status", t.status().name())
                .param("category", t.categoryId())
                .param("note", t.note())
                .param("transferGroup", t.transferGroup())
                .param("transferAccount", t.transferAccountId())
                .param("recurring", t.recurringId())
                .param("occurrence", DbCodec.date(t.occurrenceDate()));
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM transactions WHERE id = :id").param("id", id).update();
    }

    @Override
    public void deleteTransferGroup(String transferGroup) {
        jdbc.sql("DELETE FROM transactions WHERE transfer_group = :g").param("g", transferGroup).update();
    }

    @Override
    public Optional<Transaction> findById(long id) {
        return jdbc.sql(SELECT + " WHERE t.id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public List<Transaction> findByTransferGroup(String transferGroup) {
        return jdbc.sql(SELECT + " WHERE t.transfer_group = :g ORDER BY t.id").param("g", transferGroup)
                .query(MAPPER).list();
    }

    @Override
    public List<Transaction> search(TransactionQuery q) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (q.accountId() != null) {
            sql.append(" AND t.account_id = :account");
            params.put("account", q.accountId());
        }
        if (q.from() != null) {
            sql.append(" AND t.date >= :from");
            params.put("from", DbCodec.date(q.from()));
        }
        if (q.to() != null) {
            sql.append(" AND t.date <= :to");
            params.put("to", DbCodec.date(q.to()));
        }
        if (q.text() != null && !q.text().isBlank()) {
            sql.append(" AND (t.label LIKE :text ESCAPE '\\' OR t.note LIKE :text ESCAPE '\\')");
            params.put("text", "%" + escapeLike(q.text().strip()) + "%");
        }
        if (q.categoryId() != null) {
            sql.append(" AND (t.category_id = :category OR t.category_id IN (SELECT id FROM categories WHERE parent_id = :category))");
            params.put("category", q.categoryId());
        }
        if (q.statuses() != null && !q.statuses().isEmpty()) {
            sql.append(" AND t.status IN (:statuses)");
            params.put("statuses", q.statuses().stream().map(Enum::name).toList());
        }
        sql.append(" ORDER BY t.date DESC, t.id DESC LIMIT :limit OFFSET :offset");
        params.put("limit", q.limit());
        params.put("offset", q.offset());
        return jdbc.sql(sql.toString()).params(params).query(MAPPER).list();
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public Map<Long, Long> sumCountedMinorByAccount() {
        Map<Long, Long> sums = new HashMap<>();
        jdbc.sql("SELECT t.account_id, SUM(t.amount_minor) AS total FROM transactions t WHERE " + COUNTED
                        + " GROUP BY t.account_id")
                .query(rs -> {
                    sums.put(rs.getLong("account_id"), rs.getLong("total"));
                });
        return sums;
    }

    @Override
    public List<Transaction> findCounted(LocalDate from, LocalDate to) {
        return jdbc.sql(SELECT + " WHERE " + COUNTED + " AND t.date >= :from AND t.date <= :to ORDER BY t.date, t.id")
                .param("from", DbCodec.date(from)).param("to", DbCodec.date(to))
                .query(MAPPER).list();
    }

    @Override
    public List<Transaction> findPlannedUntil(LocalDate until) {
        return jdbc.sql(SELECT + " WHERE t.status = 'PLANNED' AND t.date <= :until ORDER BY t.date, t.id")
                .param("until", DbCodec.date(until))
                .query(MAPPER).list();
    }

    @Override
    public Set<OccurrenceKey> findMaterializedOccurrences(LocalDate from) {
        Set<OccurrenceKey> keys = new HashSet<>();
        jdbc.sql("SELECT DISTINCT recurring_id, occurrence_date FROM transactions "
                        + "WHERE recurring_id IS NOT NULL AND occurrence_date >= :from")
                .param("from", DbCodec.date(from))
                .query(rs -> {
                    keys.add(new OccurrenceKey(rs.getLong("recurring_id"), LocalDate.parse(rs.getString("occurrence_date"))));
                });
        return keys;
    }

    @Override
    public boolean existsForAccount(long accountId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM transactions WHERE account_id = :a OR transfer_account_id = :a)")
                .param("a", accountId).query(Integer.class).single() == 1;
    }

    /** Pour les tests et diagnostics : nombre total de lignes. */
    public long count() {
        return jdbc.sql("SELECT count(*) FROM transactions").query(Long.class).single();
    }
}

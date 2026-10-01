package com.financeapp.infra.db;

import com.financeapp.core.money.Money;
import com.financeapp.core.port.OccurrenceKey;
import com.financeapp.core.port.SearchTotals;
import com.financeapp.core.port.TransactionQuery;
import com.financeapp.core.port.TransactionRepository;
import com.financeapp.core.transaction.SplitLine;
import com.financeapp.core.transaction.Transaction;
import com.financeapp.core.transaction.TransactionStatus;
import com.financeapp.core.transaction.TransactionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
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
    /** Taille des paquets d'identifiants pour lire ventilations et etiquettes. */
    private static final int DETAIL_BATCH = 500;

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
        return tx.execute(status -> {
            String now = DbCodec.now();
            KeyHolder keys = new GeneratedKeyHolder();
            bind(jdbc.sql("""
                    INSERT INTO transactions (account_id, date, label, amount_minor, type, status, category_id, note,
                           transfer_group, transfer_account_id, recurring_id, occurrence_date, created_at, updated_at)
                    VALUES (:account, :date, :label, :amount, :type, :status, :category, :note,
                            :transferGroup, :transferAccount, :recurring, :occurrence, :now, :now)
                    """), t).param("now", now).update(keys);
            Transaction saved = t.withId(JdbcKeys.id(keys));
            writeDetails(saved);
            return saved;
        });
    }

    /** Remplace la ventilation et les etiquettes de l'operation (meme transaction SQL que la ligne). */
    private void writeDetails(Transaction t) {
        jdbc.sql("DELETE FROM transaction_splits WHERE transaction_id = :id").param("id", t.id()).update();
        jdbc.sql("DELETE FROM transaction_tags WHERE transaction_id = :id").param("id", t.id()).update();
        int position = 0;
        for (SplitLine line : t.splits()) {
            jdbc.sql("""
                    INSERT INTO transaction_splits (transaction_id, position, category_id, amount_minor)
                    VALUES (:id, :position, :category, :amount)
                    """).param("id", t.id()).param("position", position++)
                    .param("category", line.categoryId()).param("amount", line.amount().toMinorUnits()).update();
        }
        for (Long tagId : t.tagIds()) {
            jdbc.sql("INSERT INTO transaction_tags (transaction_id, tag_id) VALUES (:id, :tag)")
                    .param("id", t.id()).param("tag", tagId).update();
        }
    }

    /** Rattache ventilations et etiquettes aux operations lues (requetes groupees par paquets). */
    private List<Transaction> withDetails(List<Transaction> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Map<Long, List<SplitLine>> splits = new HashMap<>();
        Map<Long, Set<Long>> tags = new HashMap<>();
        Map<Long, Currency> currencies = new HashMap<>();
        rows.forEach(t -> currencies.put(t.id(), t.amount().currency()));
        List<Long> ids = rows.stream().map(Transaction::id).toList();
        for (int from = 0; from < ids.size(); from += DETAIL_BATCH) {
            List<Long> batch = ids.subList(from, Math.min(ids.size(), from + DETAIL_BATCH));
            jdbc.sql("SELECT transaction_id, category_id, amount_minor FROM transaction_splits "
                            + "WHERE transaction_id IN (:ids) ORDER BY transaction_id, position")
                    .param("ids", batch)
                    .query(rs -> {
                        long id = rs.getLong("transaction_id");
                        splits.computeIfAbsent(id, k -> new ArrayList<>()).add(new SplitLine(
                                DbCodec.nullableLong(rs, "category_id"),
                                Money.ofMinor(rs.getLong("amount_minor"), currencies.get(id))));
                    });
            jdbc.sql("SELECT transaction_id, tag_id FROM transaction_tags WHERE transaction_id IN (:ids)")
                    .param("ids", batch)
                    .query(rs -> {
                        tags.computeIfAbsent(rs.getLong("transaction_id"), k -> new HashSet<>()).add(rs.getLong("tag_id"));
                    });
        }
        if (splits.isEmpty() && tags.isEmpty()) {
            return rows;
        }
        List<Transaction> result = new ArrayList<>(rows.size());
        for (Transaction t : rows) {
            List<SplitLine> s = splits.getOrDefault(t.id(), List.of());
            Set<Long> g = tags.getOrDefault(t.id(), Set.of());
            result.add(s.isEmpty() && g.isEmpty() ? t : t.withDetails(s, g));
        }
        return result;
    }

    @Override
    public List<Transaction> insertAll(List<Transaction> transactions) {
        return tx.execute(status -> transactions.stream().map(this::insert).toList());
    }

    @Override
    public Transaction update(Transaction t) {
        return tx.execute(status -> {
            updateRow(t);
            writeDetails(t);
            return t;
        });
    }

    private void updateRow(Transaction t) {
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
        return jdbc.sql(SELECT + " WHERE t.id = :id").param("id", id).query(MAPPER).optional()
                .map(t -> withDetails(List.of(t)).getFirst());
    }

    @Override
    public List<Transaction> findByTransferGroup(String transferGroup) {
        return withDetails(jdbc.sql(SELECT + " WHERE t.transfer_group = :g ORDER BY t.id").param("g", transferGroup)
                .query(MAPPER).list());
    }

    @Override
    public List<Transaction> search(TransactionQuery q) {
        Map<String, Object> params = new HashMap<>();
        String sql = SELECT + " WHERE " + where(q, params) + " ORDER BY t.date DESC, t.id DESC LIMIT :limit OFFSET :offset";
        params.put("limit", q.limit());
        params.put("offset", q.offset());
        return withDetails(jdbc.sql(sql).params(params).query(MAPPER).list());
    }

    @Override
    public SearchTotals summarize(TransactionQuery q, Currency currency) {
        Map<String, Object> params = new HashMap<>();
        // Filtre par categorie : seule la part ventilee dans la categorie compte dans les totaux.
        String part = q.categoryId() == null ? "t.amount_minor"
                : "(CASE WHEN EXISTS (SELECT 1 FROM transaction_splits s WHERE s.transaction_id = t.id)"
                  + " THEN (SELECT coalesce(sum(s.amount_minor), 0) FROM transaction_splits s"
                  + " WHERE s.transaction_id = t.id AND " + inCategory("s.category_id") + ")"
                  + " ELSE t.amount_minor END)";
        String sql = """
                SELECT count(*) AS n,
                       coalesce(sum(CASE WHEN t.type = 'EXPENSE' THEN 1 ELSE 0 END), 0) AS n_expenses,
                       coalesce(sum(CASE WHEN t.type = 'EXPENSE' THEN %1$s ELSE 0 END), 0) AS expenses,
                       coalesce(sum(CASE WHEN t.type = 'INCOME' THEN %1$s ELSE 0 END), 0) AS income
                FROM transactions t JOIN accounts a ON a.id = t.account_id
                WHERE\s""".formatted(part) + where(q, params) + " AND a.currency = :currency AND t.status <> 'CANCELLED' AND t.type <> 'TRANSFER'";
        params.put("currency", currency.getCurrencyCode());
        return jdbc.sql(sql).params(params).query((rs, i) -> new SearchTotals(
                rs.getLong("n"), rs.getLong("n_expenses"),
                Money.ofMinor(rs.getLong("expenses"), currency),
                Money.ofMinor(rs.getLong("income"), currency))).single();
    }

    /** Clause WHERE commune a la recherche et a ses totaux. */
    private static String where(TransactionQuery q, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("1 = 1");
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
            // Categorie de l'operation, ou d'une de ses lignes de ventilation (sous-categories comprises).
            sql.append(" AND (").append(inCategory("t.category_id")).append(" OR t.id IN (SELECT s.transaction_id ")
                    .append("FROM transaction_splits s WHERE ").append(inCategory("s.category_id")).append("))");
            params.put("category", q.categoryId());
        }
        if (q.tagId() != null) {
            sql.append(" AND t.id IN (SELECT transaction_id FROM transaction_tags WHERE tag_id = :tag)");
            params.put("tag", q.tagId());
        }
        if (q.statuses() != null && !q.statuses().isEmpty()) {
            sql.append(" AND t.status IN (:statuses)");
            params.put("statuses", q.statuses().stream().map(Enum::name).toList());
        }
        if (q.type() != null) {
            sql.append(" AND t.type = :type");
            params.put("type", q.type().name());
        }
        // Montants filtres en valeur absolue ; conversion en centimes (devises a 2 decimales).
        if (q.minAmount() != null) {
            sql.append(" AND abs(t.amount_minor) >= :minAmount");
            params.put("minAmount", q.minAmount().movePointRight(2).setScale(0, java.math.RoundingMode.CEILING).longValueExact());
        }
        if (q.maxAmount() != null) {
            sql.append(" AND abs(t.amount_minor) <= :maxAmount");
            params.put("maxAmount", q.maxAmount().movePointRight(2).setScale(0, java.math.RoundingMode.FLOOR).longValueExact());
        }
        return sql.toString();
    }

    /** Colonne dans la categorie filtree ou une de ses sous-categories. */
    private static String inCategory(String column) {
        return "(" + column + " = :category OR " + column + " IN (SELECT id FROM categories WHERE parent_id = :category))";
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
    public long sumCountedMinorAfter(long accountId, LocalDate after) {
        Long total = jdbc.sql("SELECT COALESCE(SUM(t.amount_minor), 0) FROM transactions t WHERE " + COUNTED
                        + " AND t.account_id = :account AND t.date > :after")
                .param("account", accountId).param("after", DbCodec.date(after))
                .query(Long.class).single();
        return total == null ? 0 : total;
    }

    @Override
    public List<Transaction> findCounted(LocalDate from, LocalDate to) {
        return withDetails(jdbc.sql(SELECT + " WHERE " + COUNTED + " AND t.date >= :from AND t.date <= :to ORDER BY t.date, t.id")
                .param("from", DbCodec.date(from)).param("to", DbCodec.date(to))
                .query(MAPPER).list());
    }

    @Override
    public List<Transaction> findPlannedUntil(LocalDate until) {
        return withDetails(jdbc.sql(SELECT + " WHERE t.status = 'PLANNED' AND t.date <= :until ORDER BY t.date, t.id")
                .param("until", DbCodec.date(until))
                .query(MAPPER).list());
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

    @Override
    public List<Transaction> findNeedingReview() {
        return withDetails(jdbc.sql(SELECT + " WHERE t.needs_review = 1 ORDER BY t.date, t.id").query(MAPPER).list());
    }

    @Override
    public long countNeedingReview() {
        return jdbc.sql("SELECT count(*) FROM transactions WHERE needs_review = 1").query(Long.class).single();
    }

    @Override
    public void markReviewed(long id) {
        jdbc.sql("UPDATE transactions SET needs_review = 0 WHERE id = :id").param("id", id).update();
    }

    /** Pour les tests et diagnostics : nombre total de lignes. */
    public long count() {
        return jdbc.sql("SELECT count(*) FROM transactions").query(Long.class).single();
    }
}

package com.financeapp.infra.db;

import com.financeapp.core.port.RecurringRuleRepository;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.money.Money;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.transaction.SplitLine;
import com.financeapp.core.transaction.TransactionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JdbcRecurringRuleRepository implements RecurringRuleRepository {

    private static final String SELECT = """
            SELECT r.*, a.currency FROM recurring_transactions r JOIN accounts a ON a.id = r.account_id
            """;

    private static final RowMapper<RecurringRule> MAPPER = (rs, i) -> new RecurringRule(
            rs.getLong("id"),
            rs.getLong("account_id"),
            DbCodec.nullableLong(rs, "to_account_id"),
            TransactionType.valueOf(rs.getString("type")),
            rs.getString("label"),
            DbCodec.money(rs, "amount_minor", Currency.getInstance(rs.getString("currency"))),
            DbCodec.nullableLong(rs, "category_id"),
            Frequency.valueOf(rs.getString("frequency")),
            rs.getInt("interval_count"),
            DbCodec.date(rs, "start_date"),
            DbCodec.date(rs, "end_date"),
            DbCodec.date(rs, "tracked_from"),
            DbCodec.bool(rs, "certain"),
            DbCodec.bool(rs, "active"),
            rs.getString("note"));

    private final JdbcClient jdbc;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public JdbcRecurringRuleRepository(JdbcClient jdbc) {
        this(jdbc, null);
    }

    /** @param tx regle et ventilation ecrites ensemble ({@code null} : sans transaction englobante) */
    public JdbcRecurringRuleRepository(JdbcClient jdbc, org.springframework.transaction.support.TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public List<RecurringRule> findAll() {
        return withSplits(jdbc.sql(SELECT + " ORDER BY r.active DESC, r.label COLLATE NOCASE").query(MAPPER).list());
    }

    @Override
    public Optional<RecurringRule> findById(long id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(MAPPER).optional()
                .map(r -> withSplits(List.of(r)).getFirst());
    }

    /** Rattache la ventilation aux regles lues (peu nombreuses : une seule requete). */
    private List<RecurringRule> withSplits(List<RecurringRule> rules) {
        if (rules.isEmpty()) {
            return rules;
        }
        Map<Long, Currency> currencies = new HashMap<>();
        rules.forEach(r -> currencies.put(r.id(), r.amount().currency()));
        Map<Long, List<SplitLine>> splits = new HashMap<>();
        jdbc.sql("SELECT rule_id, category_id, amount_minor FROM recurring_splits WHERE rule_id IN (:ids) "
                        + "ORDER BY rule_id, position")
                .param("ids", rules.stream().map(RecurringRule::id).toList())
                .query(rs -> {
                    long id = rs.getLong("rule_id");
                    splits.computeIfAbsent(id, k -> new ArrayList<>()).add(new SplitLine(
                            DbCodec.nullableLong(rs, "category_id"),
                            Money.ofMinor(rs.getLong("amount_minor"), currencies.get(id))));
                });
        if (splits.isEmpty()) {
            return rules;
        }
        return rules.stream().map(r -> !splits.containsKey(r.id()) ? r : new RecurringRule(r.id(), r.accountId(),
                r.toAccountId(), r.type(), r.label(), r.amount(), null, r.frequency(), r.interval(), r.startDate(),
                r.endDate(), r.trackedFrom(), r.certain(), r.active(), r.note(), splits.get(r.id()))).toList();
    }

    @Override
    public RecurringRule save(RecurringRule r) {
        if (tx == null) {
            return saveRule(r);
        }
        return tx.execute(status -> saveRule(r));
    }

    private RecurringRule saveRule(RecurringRule r) {
        RecurringRule saved = saveRow(r);
        jdbc.sql("DELETE FROM recurring_splits WHERE rule_id = :id").param("id", saved.id()).update();
        int position = 0;
        for (SplitLine line : saved.splits()) {
            jdbc.sql("INSERT INTO recurring_splits (rule_id, position, category_id, amount_minor) "
                            + "VALUES (:id, :position, :category, :amount)")
                    .param("id", saved.id()).param("position", position++)
                    .param("category", line.categoryId()).param("amount", line.amount().toMinorUnits()).update();
        }
        return saved;
    }

    private RecurringRule saveRow(RecurringRule r) {
        String now = DbCodec.now();
        var statement = jdbc.sql(r.id() == null
                        ? """
                          INSERT INTO recurring_transactions (account_id, to_account_id, type, label, amount_minor,
                                 category_id, frequency, interval_count, start_date, end_date, tracked_from, certain,
                                 active, note, created_at, updated_at)
                          VALUES (:account, :toAccount, :type, :label, :amount, :category, :frequency, :interval,
                                  :start, :end, :trackedFrom, :certain, :active, :note, :now, :now)
                          """
                        : """
                          UPDATE recurring_transactions SET account_id = :account, to_account_id = :toAccount,
                                 type = :type, label = :label, amount_minor = :amount, category_id = :category,
                                 frequency = :frequency, interval_count = :interval, start_date = :start,
                                 end_date = :end, tracked_from = :trackedFrom, certain = :certain, active = :active,
                                 note = :note, updated_at = :now
                          WHERE id = :id
                          """)
                .param("id", r.id())
                .param("account", r.accountId())
                .param("toAccount", r.toAccountId())
                .param("type", r.type().name())
                .param("label", r.label())
                .param("amount", r.amount().toMinorUnits())
                .param("category", r.categoryId())
                .param("frequency", r.frequency().name())
                .param("interval", r.interval())
                .param("start", DbCodec.date(r.startDate()))
                .param("end", DbCodec.date(r.endDate()))
                .param("trackedFrom", DbCodec.date(r.trackedFrom()))
                .param("certain", DbCodec.bool(r.certain()))
                .param("active", DbCodec.bool(r.active()))
                .param("note", r.note())
                .param("now", now);
        if (r.id() != null) {
            statement.update();
            return r;
        }
        KeyHolder keys = new GeneratedKeyHolder();
        statement.update(keys);
        return r.withId(JdbcKeys.id(keys));
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM recurring_transactions WHERE id = :id").param("id", id).update();
    }
}

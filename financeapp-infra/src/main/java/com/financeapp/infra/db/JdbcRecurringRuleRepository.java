package com.financeapp.infra.db;

import com.financeapp.core.port.RecurringRuleRepository;
import com.financeapp.core.recurring.Frequency;
import com.financeapp.core.recurring.RecurringRule;
import com.financeapp.core.transaction.TransactionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.Currency;
import java.util.List;
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

    public JdbcRecurringRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RecurringRule> findAll() {
        return jdbc.sql(SELECT + " ORDER BY r.active DESC, r.label COLLATE NOCASE").query(MAPPER).list();
    }

    @Override
    public Optional<RecurringRule> findById(long id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public RecurringRule save(RecurringRule r) {
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

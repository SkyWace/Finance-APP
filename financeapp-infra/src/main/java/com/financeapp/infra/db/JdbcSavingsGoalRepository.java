package com.financeapp.infra.db;

import com.financeapp.core.goal.SavingsGoal;
import com.financeapp.core.port.SavingsGoalRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.Currency;
import java.util.List;
import java.util.Optional;

public final class JdbcSavingsGoalRepository implements SavingsGoalRepository {

    private static final RowMapper<SavingsGoal> MAPPER = (rs, i) -> {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        return new SavingsGoal(
                rs.getLong("id"),
                rs.getString("name"),
                DbCodec.money(rs, "target_minor", currency),
                DbCodec.date(rs, "target_date"),
                DbCodec.nullableLong(rs, "linked_account_id"),
                DbCodec.money(rs, "manual_saved_minor", currency),
                DbCodec.bool(rs, "reserve_in_available"),
                DbCodec.bool(rs, "archived"));
    };

    private final JdbcClient jdbc;

    public JdbcSavingsGoalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SavingsGoal> findAll() {
        return jdbc.sql("SELECT * FROM savings_goals ORDER BY archived, target_date IS NULL, target_date, name COLLATE NOCASE")
                .query(MAPPER).list();
    }

    @Override
    public Optional<SavingsGoal> findById(long id) {
        return jdbc.sql("SELECT * FROM savings_goals WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public SavingsGoal save(SavingsGoal g) {
        String now = DbCodec.now();
        var statement = jdbc.sql(g.id() == null
                        ? """
                          INSERT INTO savings_goals (name, target_minor, currency, target_date, linked_account_id,
                                 manual_saved_minor, reserve_in_available, archived, created_at, updated_at)
                          VALUES (:name, :target, :currency, :date, :account, :saved, :reserve, :archived, :now, :now)
                          """
                        : """
                          UPDATE savings_goals SET name = :name, target_minor = :target, currency = :currency,
                                 target_date = :date, linked_account_id = :account, manual_saved_minor = :saved,
                                 reserve_in_available = :reserve, archived = :archived, updated_at = :now
                          WHERE id = :id
                          """)
                .param("id", g.id())
                .param("name", g.name())
                .param("target", g.target().toMinorUnits())
                .param("currency", g.target().currency().getCurrencyCode())
                .param("date", DbCodec.date(g.targetDate()))
                .param("account", g.linkedAccountId())
                .param("saved", g.manualSaved().toMinorUnits())
                .param("reserve", DbCodec.bool(g.reserveInAvailable()))
                .param("archived", DbCodec.bool(g.archived()))
                .param("now", now);
        if (g.id() != null) {
            statement.update();
            return g;
        }
        KeyHolder keys = new GeneratedKeyHolder();
        statement.update(keys);
        return g.withId(JdbcKeys.id(keys));
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM savings_goals WHERE id = :id").param("id", id).update();
    }
}

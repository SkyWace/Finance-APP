package com.financeapp.infra.db;

import com.financeapp.core.budget.Budget;
import com.financeapp.core.port.BudgetRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.Currency;
import java.util.List;
import java.util.Optional;

public final class JdbcBudgetRepository implements BudgetRepository {

    private static final RowMapper<Budget> MAPPER = (rs, i) -> new Budget(
            rs.getLong("id"),
            rs.getLong("category_id"),
            DbCodec.money(rs, "limit_minor", Currency.getInstance(rs.getString("currency"))),
            DbCodec.bool(rs, "reserve_in_available"),
            DbCodec.bool(rs, "active"));

    private final JdbcClient jdbc;

    public JdbcBudgetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Budget> findAll() {
        return jdbc.sql("SELECT * FROM budgets ORDER BY active DESC, id").query(MAPPER).list();
    }

    @Override
    public Optional<Budget> findById(long id) {
        return jdbc.sql("SELECT * FROM budgets WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public Budget save(Budget b) {
        String now = DbCodec.now();
        var statement = jdbc.sql(b.id() == null
                        ? """
                          INSERT INTO budgets (category_id, limit_minor, currency, reserve_in_available, active, created_at, updated_at)
                          VALUES (:category, :limit, :currency, :reserve, :active, :now, :now)
                          """
                        : """
                          UPDATE budgets SET category_id = :category, limit_minor = :limit, currency = :currency,
                                 reserve_in_available = :reserve, active = :active, updated_at = :now
                          WHERE id = :id
                          """)
                .param("id", b.id())
                .param("category", b.categoryId())
                .param("limit", b.limit().toMinorUnits())
                .param("currency", b.limit().currency().getCurrencyCode())
                .param("reserve", DbCodec.bool(b.reserveInAvailable()))
                .param("active", DbCodec.bool(b.active()))
                .param("now", now);
        if (b.id() != null) {
            statement.update();
            return b;
        }
        KeyHolder keys = new GeneratedKeyHolder();
        statement.update(keys);
        return b.withId(JdbcKeys.id(keys));
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM budgets WHERE id = :id").param("id", id).update();
    }
}

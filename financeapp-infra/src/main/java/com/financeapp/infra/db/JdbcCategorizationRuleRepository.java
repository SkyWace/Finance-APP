package com.financeapp.infra.db;

import com.financeapp.core.categorization.CategorizationRule;
import com.financeapp.core.port.CategorizationRuleRepository;
import com.financeapp.core.transaction.TransactionType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.List;
import java.util.Optional;

public final class JdbcCategorizationRuleRepository implements CategorizationRuleRepository {

    private static final RowMapper<CategorizationRule> MAPPER = (rs, i) -> {
        String appliesTo = rs.getString("applies_to");
        return new CategorizationRule(
                rs.getLong("id"),
                rs.getString("pattern"),
                rs.getLong("category_id"),
                appliesTo == null ? null : TransactionType.valueOf(appliesTo),
                DbCodec.bool(rs, "active"));
    };

    private final JdbcClient jdbc;

    public JdbcCategorizationRuleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CategorizationRule> findAll() {
        return jdbc.sql("SELECT * FROM categorization_rules ORDER BY pattern COLLATE NOCASE").query(MAPPER).list();
    }

    @Override
    public Optional<CategorizationRule> findById(long id) {
        return jdbc.sql("SELECT * FROM categorization_rules WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public CategorizationRule save(CategorizationRule r) {
        var statement = jdbc.sql(r.id() == null
                        ? "INSERT INTO categorization_rules (pattern, category_id, applies_to, active, created_at) "
                          + "VALUES (:pattern, :category, :appliesTo, :active, :now)"
                        : "UPDATE categorization_rules SET pattern = :pattern, category_id = :category, "
                          + "applies_to = :appliesTo, active = :active WHERE id = :id")
                .param("id", r.id())
                .param("pattern", r.pattern())
                .param("category", r.categoryId())
                .param("appliesTo", r.appliesTo() == null ? null : r.appliesTo().name())
                .param("active", DbCodec.bool(r.active()))
                .param("now", DbCodec.now());
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
        jdbc.sql("DELETE FROM categorization_rules WHERE id = :id").param("id", id).update();
    }
}

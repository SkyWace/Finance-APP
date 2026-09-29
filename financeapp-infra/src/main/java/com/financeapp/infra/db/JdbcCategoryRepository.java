package com.financeapp.infra.db;

import com.financeapp.core.category.Category;
import com.financeapp.core.category.CategoryKind;
import com.financeapp.core.port.CategoryRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.List;
import java.util.Optional;

public final class JdbcCategoryRepository implements CategoryRepository {

    private static final RowMapper<Category> MAPPER = (rs, i) -> new Category(
            rs.getLong("id"),
            DbCodec.nullableLong(rs, "parent_id"),
            rs.getString("name"),
            CategoryKind.valueOf(rs.getString("kind")),
            rs.getString("icon"),
            rs.getString("color"),
            DbCodec.bool(rs, "archived"),
            rs.getInt("sort_order"),
            rs.getString("system_code"));

    private final JdbcClient jdbc;

    public JdbcCategoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Category> findAll() {
        return jdbc.sql("SELECT * FROM categories ORDER BY sort_order, name COLLATE NOCASE").query(MAPPER).list();
    }

    @Override
    public Optional<Category> findById(long id) {
        return jdbc.sql("SELECT * FROM categories WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public Category save(Category c) {
        var statement = jdbc.sql(c.id() == null
                        ? """
                          INSERT INTO categories (parent_id, name, kind, icon, color, archived, sort_order, system_code)
                          VALUES (:parent, :name, :kind, :icon, :color, :archived, :sortOrder, :code)
                          """
                        : """
                          UPDATE categories SET parent_id = :parent, name = :name, kind = :kind, icon = :icon,
                                 color = :color, archived = :archived, sort_order = :sortOrder, system_code = :code
                          WHERE id = :id
                          """)
                .param("id", c.id())
                .param("parent", c.parentId())
                .param("name", c.name())
                .param("kind", c.kind().name())
                .param("icon", c.icon())
                .param("color", c.color())
                .param("archived", DbCodec.bool(c.archived()))
                .param("sortOrder", c.sortOrder())
                .param("code", c.systemCode());
        if (c.id() != null) {
            statement.update();
            return c;
        }
        KeyHolder keys = new GeneratedKeyHolder();
        statement.update(keys);
        return c.withId(JdbcKeys.id(keys));
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM categories WHERE id = :id").param("id", id).update();
    }

    @Override
    public long countUsages(long id) {
        return jdbc.sql("""
                        SELECT (SELECT count(*) FROM transactions WHERE category_id = :id)
                             + (SELECT count(*) FROM recurring_transactions WHERE category_id = :id)
                             + (SELECT count(*) FROM categories WHERE parent_id = :id)
                        """)
                .param("id", id).query(Long.class).single();
    }
}

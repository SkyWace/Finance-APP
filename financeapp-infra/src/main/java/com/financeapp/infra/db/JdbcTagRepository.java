package com.financeapp.infra.db;

import com.financeapp.core.port.TagRepository;
import com.financeapp.core.tag.Tag;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class JdbcTagRepository implements TagRepository {

    private static final RowMapper<Tag> MAPPER = (rs, i) -> new Tag(rs.getLong("id"), rs.getString("name"));

    private final JdbcClient jdbc;

    public JdbcTagRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Tag> findAll() {
        return jdbc.sql("SELECT id, name FROM tags ORDER BY name").query(MAPPER).list();
    }

    @Override
    public Optional<Tag> findById(long id) {
        return jdbc.sql("SELECT id, name FROM tags WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public Optional<Tag> findByName(String name) {
        return jdbc.sql("SELECT id, name FROM tags WHERE name = :name").param("name", name.strip()).query(MAPPER).optional();
    }

    @Override
    public Tag save(Tag tag) {
        if (tag.id() == null) {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO tags (name, created_at) VALUES (:name, :now)")
                    .param("name", tag.name()).param("now", DbCodec.now()).update(keys);
            return tag.withId(JdbcKeys.id(keys));
        }
        jdbc.sql("UPDATE tags SET name = :name WHERE id = :id").param("name", tag.name()).param("id", tag.id()).update();
        return tag;
    }

    @Override
    public void delete(long id) {
        jdbc.sql("DELETE FROM tags WHERE id = :id").param("id", id).update(); // retiree des operations (cascade)
    }

    @Override
    public Map<Long, Long> usageCounts() {
        Map<Long, Long> counts = new HashMap<>();
        jdbc.sql("SELECT tag_id, count(*) AS n FROM transaction_tags GROUP BY tag_id")
                .query(rs -> {
                    counts.put(rs.getLong("tag_id"), rs.getLong("n"));
                });
        return counts;
    }
}

package com.financeapp.infra.db;

import com.financeapp.core.port.SettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;

public final class JdbcSettingsRepository implements SettingsRepository {

    private final JdbcClient jdbc;

    public JdbcSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> get(String key) {
        return jdbc.sql("SELECT value FROM settings WHERE key = :key").param("key", key).query(String.class).optional();
    }

    @Override
    public void put(String key, String value) {
        jdbc.sql("INSERT INTO settings (key, value) VALUES (:key, :value) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
                .param("key", key).param("value", value).update();
    }
}

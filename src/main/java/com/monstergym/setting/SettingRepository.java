package com.monstergym.setting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Lectura de la tabla setting (clave → valor de texto). */
@Repository
public class SettingRepository {

    private final JdbcTemplate jdbc;

    public SettingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String key) {
        return jdbc.queryForList("SELECT value FROM setting WHERE key = ?", String.class, key).stream().findFirst();
    }

    public int getInt(String key, int defaultValue) {
        try {
            return get(key).map(String::trim).map(Integer::parseInt).orElse(defaultValue);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBool(String key, boolean defaultValue) {
        return get(key).map(v -> "1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim())).orElse(defaultValue);
    }
}

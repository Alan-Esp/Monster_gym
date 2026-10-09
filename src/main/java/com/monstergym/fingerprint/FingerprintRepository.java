package com.monstergym.fingerprint;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Plantillas de huella en member.fingerprint_template (BLOB opaco del SDK, nunca la imagen). */
@Repository
public class FingerprintRepository {

    public record Row(long memberId, byte[] template) {
    }

    private final JdbcTemplate jdbc;

    public FingerprintRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Socios activos con huella, primero los que vinieron más recientemente (la búsqueda 1:N acaba antes). */
    public List<Row> findAllActive() {
        return jdbc.query("""
                SELECT m.id, m.fingerprint_template FROM member m
                WHERE m.active = 1 AND m.fingerprint_template IS NOT NULL
                ORDER BY (SELECT MAX(v.checked_in_at) FROM visit v WHERE v.member_id = m.id) DESC NULLS LAST, m.id
                """, (rs, i) -> new Row(rs.getLong(1), rs.getBytes(2)));
    }

    public void save(long memberId, byte[] template) {
        jdbc.update("UPDATE member SET fingerprint_template = ? WHERE id = ?", template, memberId);
    }

    public int countActive() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM member WHERE active = 1 AND fingerprint_template IS NOT NULL", Integer.class);
        return n == null ? 0 : n;
    }
}

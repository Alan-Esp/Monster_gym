package com.monstergym.visit;

import com.monstergym.visit.VisitDtos.VisitItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Las fechas se comparan como texto ISO (YYYY-MM-DDTHH:MM:SS), que ordena igual que el tiempo. */
@Repository
public class VisitRepository {

    private static final String SELECT_ITEM = """
            SELECT v.id, v.checked_in_at, v.member_id, m.number, m.full_name, v.method, v.status_at_entry
            FROM visit v JOIN member m ON m.id = v.member_id
            """;

    private static final RowMapper<VisitItem> ITEM = (rs, i) -> new VisitItem(
            rs.getLong("id"),
            rs.getString("checked_in_at").substring(11, 16),
            rs.getString("checked_in_at"),
            rs.getLong("member_id"),
            rs.getString("number"),
            rs.getString("full_name"),
            rs.getString("method"),
            rs.getString("status_at_entry"));

    private final JdbcTemplate jdbc;

    public VisitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Última entrada del socio hasta upTo (inclusive); ignora registros con fecha futura. */
    public Optional<String> lastVisitAt(long memberId, String upTo) {
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT MAX(checked_in_at) FROM visit WHERE member_id = ? AND checked_in_at <= ?",
                String.class, memberId, upTo));
    }

    /** Última visita del socio hasta upTo (inclusive). */
    public Optional<VisitItem> lastVisit(long memberId, String upTo) {
        return jdbc.query(SELECT_ITEM + " WHERE v.member_id = ? AND v.checked_in_at <= ? "
                + "ORDER BY v.checked_in_at DESC, v.id DESC LIMIT 1", ITEM, memberId, upTo).stream().findFirst();
    }

    public long insert(long memberId, String checkedInAt, String method, String statusAtEntry, long employeeId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO visit (member_id, checked_in_at, method, status_at_entry, employee_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, memberId);
            ps.setString(2, checkedInAt);
            ps.setString(3, method);
            ps.setString(4, statusAtEntry);
            ps.setLong(5, employeeId);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** Visitas con from <= checked_in_at < to, de la más reciente a la más antigua. */
    public List<VisitItem> findBetween(String from, String to, int limit) {
        return jdbc.query(SELECT_ITEM + " WHERE v.checked_in_at >= ? AND v.checked_in_at < ? "
                + "ORDER BY v.checked_in_at DESC, v.id DESC LIMIT ?", ITEM, from, to, limit);
    }

    public List<VisitItem> recent(int limit) {
        return jdbc.query(SELECT_ITEM + " ORDER BY v.checked_in_at DESC, v.id DESC LIMIT ?", ITEM, limit);
    }

    public int countBetween(String from, String to) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM visit WHERE checked_in_at >= ? AND checked_in_at < ?", Integer.class, from, to);
        return n == null ? 0 : n;
    }

    public int countFingerprintBetween(String from, String to) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM visit WHERE method = 'huella' "
                + "AND checked_in_at >= ? AND checked_in_at < ?", Integer.class, from, to);
        return n == null ? 0 : n;
    }

    /** Visitas por hora del día (0-23) entre from y to. */
    public Map<Integer, Integer> countByHour(String from, String to) {
        Map<Integer, Integer> byHour = new HashMap<>();
        jdbc.query("""
                SELECT CAST(substr(checked_in_at, 12, 2) AS INTEGER) AS h, COUNT(*) AS n FROM visit
                WHERE checked_in_at >= ? AND checked_in_at < ? GROUP BY h
                """, rs -> { byHour.put(rs.getInt("h"), rs.getInt("n")); }, from, to);
        return byHour;
    }
}

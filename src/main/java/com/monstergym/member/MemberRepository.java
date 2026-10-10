package com.monstergym.member;

import com.monstergym.common.SearchText;
import com.monstergym.member.MemberDtos.MemberItem;
import com.monstergym.member.MemberDtos.Visit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class MemberRepository {

    /** Plan de la membresía más reciente con pago no cancelado. */
    private static final String CURRENT_PLAN = """
            (SELECT pl.name FROM membership ms
             JOIN payment p ON p.id = ms.payment_id AND p.voided_at IS NULL
             JOIN plan pl ON pl.id = ms.plan_id
             WHERE ms.member_id = m.id
             ORDER BY ms.ends_on DESC, ms.id DESC LIMIT 1)
            """;

    private static final RowMapper<Member> MAPPER = (rs, i) -> new Member(
            rs.getLong("id"),
            rs.getString("number"),
            rs.getString("full_name"),
            rs.getString("phone"),
            rs.getString("notes"),
            rs.getString("joined_on"),
            rs.getInt("active") == 1,
            rs.getBytes("fingerprint_template") != null);

    private static final RowMapper<MemberItem> ITEM = (rs, i) -> new MemberItem(
            rs.getLong("id"),
            rs.getString("number"),
            rs.getString("full_name"),
            rs.getString("phone"),
            rs.getString("plan"),
            rs.getString("valid_until"),
            rs.getString("status"));

    public record StatusRow(String plan, String validUntil, Integer daysLeft, String status) {
    }

    /** Condición SQL de búsqueda por nombre (search_key), número de socio o teléfono. */
    record Filter(String sql, List<Object> args) {
        static Filter of(String query) {
            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder("m.active = 1");
            String key = SearchText.key(query);
            String digits = SearchText.digits(query);
            if (!key.isEmpty() || !digits.isEmpty()) {
                List<String> any = new ArrayList<>();
                if (!key.isEmpty()) {
                    any.add("m.search_key LIKE ? ESCAPE '\\'");
                    args.add(SearchText.likeContains(key));
                }
                if (!digits.isEmpty()) {
                    any.add("m.number LIKE ? ESCAPE '\\'");
                    any.add("m.phone LIKE ? ESCAPE '\\'");
                    args.add(SearchText.likeContains(digits));
                    args.add(SearchText.likeContains(digits));
                }
                sql.append(" AND (").append(String.join(" OR ", any)).append(")");
            }
            return new Filter(sql.toString(), args);
        }
    }

    private final JdbcTemplate jdbc;

    public MemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Member> findById(long id) {
        return jdbc.query("SELECT * FROM member WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public List<MemberItem> search(String query, String status, int limit, int offset) {
        Filter f = Filter.of(query);
        List<Object> args = new ArrayList<>(f.args());
        String statusSql = "";
        if (status != null) {
            statusSql = " AND s.status = ?";
            args.add(status);
        }
        args.add(limit);
        args.add(offset);
        return jdbc.query("SELECT m.id, m.number, m.full_name, m.phone, s.valid_until, s.status, "
                + CURRENT_PLAN + " AS plan "
                + "FROM member m JOIN v_member_status s ON s.member_id = m.id "
                + "WHERE " + f.sql() + statusSql
                + " ORDER BY m.search_key, m.number LIMIT ? OFFSET ?", ITEM, args.toArray());
    }

    /** Conteo por estado de los socios que cumplen la búsqueda. */
    public Map<String, Integer> countByStatus(String query) {
        Filter f = Filter.of(query);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("activa", 0);
        counts.put("por_vencer", 0);
        counts.put("vencida", 0);
        jdbc.query("SELECT s.status, COUNT(*) AS n FROM member m JOIN v_member_status s ON s.member_id = m.id "
                        + "WHERE " + f.sql() + " GROUP BY s.status",
                rs -> { counts.put(rs.getString("status"), rs.getInt("n")); }, f.args().toArray());
        return counts;
    }

    public int countActive() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE active = 1", Integer.class);
        return n == null ? 0 : n;
    }

    public StatusRow status(long memberId) {
        return jdbc.queryForObject("SELECT s.valid_until, s.days_left, s.status, " + CURRENT_PLAN + " AS plan "
                        + "FROM member m JOIN v_member_status s ON s.member_id = m.id WHERE m.id = ?",
                (rs, i) -> new StatusRow(rs.getString("plan"), rs.getString("valid_until"),
                        rs.getObject("days_left") == null ? null : rs.getInt("days_left"), rs.getString("status")),
                memberId);
    }

    public List<Visit> recentVisits(long memberId, int limit) {
        return jdbc.query("""
                SELECT checked_in_at, method, status_at_entry FROM visit
                WHERE member_id = ? ORDER BY checked_in_at DESC LIMIT ?
                """, (rs, i) -> new Visit(rs.getString("checked_in_at"), rs.getString("method"),
                rs.getString("status_at_entry")), memberId, limit);
    }

    /** Siguiente número consecutivo (sin formato). */
    public int nextNumber() {
        Integer max = jdbc.queryForObject("SELECT MAX(CAST(number AS INTEGER)) FROM member", Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    public long insert(String number, String fullName, String phone, String notes, String joinedOn) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO member (number, full_name, phone, notes, joined_on, search_key)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setString(1, number);
            ps.setString(2, fullName);
            ps.setString(3, phone);
            ps.setString(4, notes);
            ps.setString(5, joinedOn);
            ps.setString(6, SearchText.key(fullName));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void update(long id, String fullName, String phone, String notes) {
        jdbc.update("UPDATE member SET full_name = ?, phone = ?, notes = ?, search_key = ? WHERE id = ?",
                fullName, phone, notes, SearchText.key(fullName), id);
    }

    /** Baja lógica: el socio deja de aparecer y se borra su huella; pagos y visitas se conservan. */
    public void deactivate(long id) {
        jdbc.update("UPDATE member SET active = 0, fingerprint_template = NULL, fingerprint_consent_at = NULL "
                + "WHERE id = ?", id);
    }

    /** Llena search_key de los socios que aún no la tienen (seed de dev o datos previos a V3). */
    public int backfillSearchKeys() {
        List<Map.Entry<Long, String>> pending = jdbc.query(
                "SELECT id, full_name FROM member WHERE search_key = ''",
                (rs, i) -> Map.entry(rs.getLong("id"), rs.getString("full_name")));
        pending.forEach(e -> jdbc.update("UPDATE member SET search_key = ? WHERE id = ?",
                SearchText.key(e.getValue()), e.getKey()));
        return pending.size();
    }
}

package com.monstergym.dashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Consultas del dashboard que no pertenecen a visitas. Rangos de fecha como texto ISO [from, to). */
@Repository
public class DashboardRepository {

    public record TopProduct(String name, int sold) {
    }

    private final JdbcTemplate jdbc;

    public DashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Socios activos por estado (activa, por_vencer, vencida) según v_member_status. */
    public Map<String, Integer> membersByStatus() {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("SELECT s.status, COUNT(*) AS n FROM member m JOIN v_member_status s ON s.member_id = m.id "
                + "WHERE m.active = 1 GROUP BY s.status", rs -> { counts.put(rs.getString("status"), rs.getInt("n")); });
        return counts;
    }

    public List<TopProduct> topProducts(String from, String to, int limit) {
        return jdbc.query("""
                SELECT pr.name, SUM(si.qty) AS sold
                FROM sale s
                JOIN sale_item si ON si.sale_id = s.id
                JOIN product pr ON pr.id = si.product_id
                WHERE s.voided_at IS NULL AND s.created_at >= ? AND s.created_at < ?
                GROUP BY pr.id ORDER BY sold DESC, pr.name LIMIT ?
                """, (rs, i) -> new TopProduct(rs.getString("name"), rs.getInt("sold")), from, to, limit);
    }

    public long membershipIncome(String from, String to) {
        return sum("SELECT SUM(amount_cents) FROM payment WHERE voided_at IS NULL AND paid_at >= ? AND paid_at < ?",
                from, to);
    }

    public long fridgeIncome(String from, String to) {
        return sum("SELECT SUM(total_cents) FROM sale WHERE voided_at IS NULL AND created_at >= ? AND created_at < ?",
                from, to);
    }

    private long sum(String sql, String from, String to) {
        Long n = jdbc.queryForObject(sql, Long.class, from, to);
        return n == null ? 0 : n;
    }
}

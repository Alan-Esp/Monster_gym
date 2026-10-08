package com.monstergym.shift;

import com.monstergym.shift.ShiftSummary.PlanLine;
import com.monstergym.shift.ShiftSummary.ProductLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class ShiftRepository {

    private static final String SELECT = """
            SELECT s.id, s.employee_id, e.name AS employee_name, s.opened_at, s.closed_at,
                   s.opening_cash_cents, s.counted_cash_cents, s.notes
            FROM shift s JOIN employee e ON e.id = s.employee_id
            """;

    private static final RowMapper<Shift> MAPPER = (rs, i) -> new Shift(
            rs.getLong("id"),
            rs.getLong("employee_id"),
            rs.getString("employee_name"),
            rs.getString("opened_at"),
            rs.getString("closed_at"),
            rs.getLong("opening_cash_cents"),
            rs.getObject("counted_cash_cents") == null ? null : rs.getLong("counted_cash_cents"),
            rs.getString("notes"));

    private final JdbcTemplate jdbc;

    public ShiftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Shift> findOpenByEmployee(long employeeId) {
        return jdbc.query(SELECT + " WHERE s.employee_id = ? AND s.closed_at IS NULL", MAPPER, employeeId)
                .stream().findFirst();
    }

    public Optional<Shift> findById(long id) {
        return jdbc.query(SELECT + " WHERE s.id = ?", MAPPER, id).stream().findFirst();
    }

    public List<Shift> findRecent(int limit) {
        return jdbc.query(SELECT + " ORDER BY s.opened_at DESC, s.id DESC LIMIT ?", MAPPER, limit);
    }

    public long insert(long employeeId, String openedAt, long openingCashCents) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO shift (employee_id, opened_at, opening_cash_cents) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, employeeId);
            ps.setString(2, openedAt);
            ps.setLong(3, openingCashCents);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** Cierra el turno solo si sigue abierto. Devuelve false si ya estaba cerrado. */
    public boolean close(long id, String closedAt, long countedCashCents, String notes) {
        return jdbc.update(
                "UPDATE shift SET closed_at = ?, counted_cash_cents = ?, notes = ? WHERE id = ? AND closed_at IS NULL",
                closedAt, countedCashCents, notes, id) == 1;
    }

    // ── Totales del corte (excluyen pagos y ventas cancelados) ──────────────────

    public Map<String, Long> paymentTotalsByMethod(long shiftId) {
        return totalsByMethod("""
                SELECT method, SUM(amount_cents) AS total FROM payment
                WHERE shift_id = ? AND voided_at IS NULL GROUP BY method
                """, shiftId);
    }

    public Map<String, Long> saleTotalsByMethod(long shiftId) {
        return totalsByMethod("""
                SELECT method, SUM(total_cents) AS total FROM sale
                WHERE shift_id = ? AND voided_at IS NULL GROUP BY method
                """, shiftId);
    }

    public List<PlanLine> paymentsByPlan(long shiftId) {
        return jdbc.query("""
                SELECT pl.name, COUNT(*) AS n, SUM(p.amount_cents) AS total
                FROM payment p JOIN plan pl ON pl.id = p.plan_id
                WHERE p.shift_id = ? AND p.voided_at IS NULL
                GROUP BY pl.id ORDER BY pl.days
                """, (rs, i) -> new PlanLine(rs.getString("name"), rs.getInt("n"), rs.getLong("total")), shiftId);
    }

    public List<ProductLine> salesByProduct(long shiftId) {
        return jdbc.query("""
                SELECT pr.name, SUM(si.qty) AS qty, SUM(si.qty * si.unit_price_cents) AS total
                FROM sale s
                JOIN sale_item si ON si.sale_id = s.id
                JOIN product pr ON pr.id = si.product_id
                WHERE s.shift_id = ? AND s.voided_at IS NULL
                GROUP BY pr.id ORDER BY pr.name
                """, (rs, i) -> new ProductLine(rs.getString("name"), rs.getInt("qty"), rs.getLong("total")),
                shiftId);
    }

    public int saleCount(long shiftId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sale WHERE shift_id = ? AND voided_at IS NULL", Integer.class, shiftId);
        return n == null ? 0 : n;
    }

    private Map<String, Long> totalsByMethod(String sql, long shiftId) {
        return jdbc.query(sql, (rs, i) -> Map.entry(rs.getString("method"), rs.getLong("total")), shiftId)
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}

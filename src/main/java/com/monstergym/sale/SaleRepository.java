package com.monstergym.sale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class SaleRepository {

    private static final String SELECT = """
            SELECT s.id, s.created_at, s.method, s.total_cents, s.shift_id, s.voided_at, s.void_reason,
                   e.name AS employee_name
            FROM sale s JOIN employee e ON e.id = s.employee_id
            """;

    private final JdbcTemplate jdbc;

    public SaleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertSale(long shiftId, long employeeId, long totalCents, String method, String createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO sale (shift_id, employee_id, total_cents, method, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, shiftId);
            ps.setLong(2, employeeId);
            ps.setLong(3, totalCents);
            ps.setString(4, method);
            ps.setString(5, createdAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void insertItem(long saleId, long productId, int qty, long unitPriceCents) {
        jdbc.update("INSERT INTO sale_item (sale_id, product_id, qty, unit_price_cents) VALUES (?, ?, ?, ?)",
                saleId, productId, qty, unitPriceCents);
    }

    public Optional<SaleReceipt> findById(long id) {
        return jdbc.query(SELECT + " WHERE s.id = ?", (rs, i) -> receipt(rs), id).stream().findFirst();
    }

    public List<SaleReceipt> findByShift(long shiftId, int limit) {
        return jdbc.query(SELECT + " WHERE s.shift_id = ? ORDER BY s.created_at DESC, s.id DESC LIMIT ?",
                (rs, i) -> receipt(rs), shiftId, limit);
    }

    public List<SaleReceipt.Item> items(long saleId) {
        return jdbc.query("""
                SELECT si.product_id, p.name, si.qty, si.unit_price_cents
                FROM sale_item si JOIN product p ON p.id = si.product_id
                WHERE si.sale_id = ? ORDER BY si.id
                """, (rs, i) -> new SaleReceipt.Item(rs.getLong("product_id"), rs.getString("name"), rs.getInt("qty"),
                rs.getLong("unit_price_cents"), rs.getInt("qty") * rs.getLong("unit_price_cents")), saleId);
    }

    /** Devuelve true si la venta estaba vigente y quedó cancelada. */
    public boolean voidSale(long id, String voidedAt, String reason) {
        return jdbc.update("UPDATE sale SET voided_at = ?, void_reason = ? WHERE id = ? AND voided_at IS NULL",
                voidedAt, reason, id) == 1;
    }

    private SaleReceipt receipt(java.sql.ResultSet rs) throws java.sql.SQLException {
        long id = rs.getLong("id");
        return new SaleReceipt(id, String.format("%06d", id), rs.getString("created_at"), rs.getString("method"),
                rs.getLong("total_cents"), rs.getString("employee_name"), rs.getLong("shift_id"),
                rs.getString("voided_at"), rs.getString("void_reason"), null);
    }
}

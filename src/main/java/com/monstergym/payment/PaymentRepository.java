package com.monstergym.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class PaymentRepository {

    private static final String SELECT_RECEIPT = """
            SELECT p.id, p.amount_cents, p.method, p.paid_at, p.shift_id, p.voided_at, p.void_reason,
                   m.id AS member_id, m.number, m.full_name,
                   pl.id AS plan_id, pl.name AS plan_name, pl.days,
                   e.name AS employee_name,
                   ms.starts_on, ms.ends_on
            FROM payment p
            JOIN member m      ON m.id = p.member_id
            JOIN plan pl       ON pl.id = p.plan_id
            JOIN employee e    ON e.id = p.employee_id
            JOIN membership ms ON ms.payment_id = p.id
            """;

    private static final RowMapper<PaymentReceipt> RECEIPT = (rs, i) -> new PaymentReceipt(
            rs.getLong("id"),
            PaymentReceipt.folio(rs.getLong("id")),
            new PaymentReceipt.Member(rs.getLong("member_id"), rs.getString("number"), rs.getString("full_name")),
            new PaymentReceipt.Plan(rs.getLong("plan_id"), rs.getString("plan_name"), rs.getInt("days")),
            rs.getString("starts_on"),
            rs.getString("ends_on"),
            rs.getLong("amount_cents"),
            rs.getString("method"),
            rs.getString("employee_name"),
            rs.getString("paid_at"),
            rs.getLong("shift_id"),
            rs.getString("voided_at"),
            rs.getString("void_reason"));

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Vigencia actual: MAX(ends_on) de las membresías cuyo pago no está cancelado. */
    public Optional<LocalDate> validUntil(long memberId) {
        String value = jdbc.queryForObject("""
                SELECT MAX(ms.ends_on) FROM membership ms
                JOIN payment p ON p.id = ms.payment_id AND p.voided_at IS NULL
                WHERE ms.member_id = ?
                """, String.class, memberId);
        return Optional.ofNullable(value).map(LocalDate::parse);
    }

    public long insertPayment(long memberId, long planId, long amountCents, String method,
                              long shiftId, long employeeId, String paidAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO payment (member_id, plan_id, amount_cents, method, shift_id, employee_id, paid_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, memberId);
            ps.setLong(2, planId);
            ps.setLong(3, amountCents);
            ps.setString(4, method);
            ps.setLong(5, shiftId);
            ps.setLong(6, employeeId);
            ps.setString(7, paidAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public void insertMembership(long memberId, long planId, long paymentId, LocalDate startsOn, LocalDate endsOn) {
        jdbc.update("""
                INSERT INTO membership (member_id, plan_id, payment_id, starts_on, ends_on)
                VALUES (?, ?, ?, ?, ?)
                """, memberId, planId, paymentId, startsOn.toString(), endsOn.toString());
    }

    public Optional<PaymentReceipt> findReceipt(long paymentId) {
        return jdbc.query(SELECT_RECEIPT + " WHERE p.id = ?", RECEIPT, paymentId).stream().findFirst();
    }

    public List<PaymentReceipt> findByShift(long shiftId) {
        return jdbc.query(SELECT_RECEIPT + " WHERE p.shift_id = ? ORDER BY p.paid_at DESC, p.id DESC",
                RECEIPT, shiftId);
    }

    public List<PaymentReceipt> findByMember(long memberId) {
        return jdbc.query(SELECT_RECEIPT + " WHERE p.member_id = ? ORDER BY p.paid_at DESC, p.id DESC",
                RECEIPT, memberId);
    }

    /** Devuelve true si el pago estaba vigente y quedó cancelado. */
    public boolean voidPayment(long paymentId, String voidedAt, String reason) {
        return jdbc.update("UPDATE payment SET voided_at = ?, void_reason = ? WHERE id = ? AND voided_at IS NULL",
                voidedAt, reason, paymentId) == 1;
    }
}

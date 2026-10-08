package com.monstergym.report;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {

    private static final String[] MONTHS = {"Ene", "Feb", "Mar", "Abr", "May", "Jun", "Jul", "Ago", "Sep", "Oct", "Nov", "Dic"};

    /** Ingresos de un mes (sin cancelados). month = "YYYY-MM"; label = "Oct". */
    public record MonthIncome(String month, String label, long membershipsCents, long fridgeCents, long totalCents,
                              boolean current) {
    }

    /**
     * activeMembers: vigentes (activa + por vencer). newThisMonth: altas desde el día 1.
     * renewalRate: de los socios cuya membresía venció en los últimos 30 días, % que hoy está vigente
     * (null si no venció ninguna).
     */
    public record Summary(int activeMembers, int newThisMonth, Integer renewalRate, int expiredLast30Days) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReportService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MonthIncome> incomeByMonth(int months) {
        YearMonth now = YearMonth.now(clock);
        YearMonth first = now.minusMonths(months - 1L);
        String from = first.atDay(1).toString(), to = now.plusMonths(1).atDay(1).toString();
        Map<String, Long> mem = byMonth("SELECT substr(paid_at, 1, 7) AS m, SUM(amount_cents) AS t FROM payment "
                + "WHERE voided_at IS NULL AND paid_at >= ? AND paid_at < ? GROUP BY m", from, to);
        Map<String, Long> fridge = byMonth("SELECT substr(created_at, 1, 7) AS m, SUM(total_cents) AS t FROM sale "
                + "WHERE voided_at IS NULL AND created_at >= ? AND created_at < ? GROUP BY m", from, to);
        List<MonthIncome> out = new ArrayList<>();
        for (YearMonth ym = first; !ym.isAfter(now); ym = ym.plusMonths(1)) {
            String key = ym.toString();
            long m = mem.getOrDefault(key, 0L), f = fridge.getOrDefault(key, 0L);
            out.add(new MonthIncome(key, MONTHS[ym.getMonthValue() - 1], m, f, m + f, ym.equals(now)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        LocalDate today = LocalDate.now(clock);
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM member m JOIN v_member_status s ON s.member_id = m.id "
                + "WHERE m.active = 1 AND s.status IN ('activa', 'por_vencer')", Integer.class);
        Integer fresh = jdbc.queryForObject("SELECT COUNT(*) FROM member WHERE active = 1 AND joined_on >= ?",
                Integer.class, today.withDayOfMonth(1).toString());
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT COUNT(*) AS expired,
                       COALESCE(SUM(CASE WHEN s.valid_until >= ? THEN 1 ELSE 0 END), 0) AS renewed
                FROM (SELECT DISTINCT ms.member_id
                      FROM membership ms
                      JOIN payment p ON p.id = ms.payment_id AND p.voided_at IS NULL
                      JOIN member m ON m.id = ms.member_id AND m.active = 1
                      WHERE ms.ends_on >= ? AND ms.ends_on < ?) e
                JOIN v_member_status s ON s.member_id = e.member_id
                """, today.toString(), today.minusDays(30).toString(), today.toString());
        int expired = ((Number) r.get("expired")).intValue(), renewed = ((Number) r.get("renewed")).intValue();
        return new Summary(active == null ? 0 : active, fresh == null ? 0 : fresh,
                expired == 0 ? null : Math.round(renewed * 100f / expired), expired);
    }

    private Map<String, Long> byMonth(String sql, String from, String to) {
        Map<String, Long> out = new HashMap<>();
        jdbc.query(sql, rs -> { out.put(rs.getString("m"), rs.getLong("t")); }, from, to);
        return out;
    }
}

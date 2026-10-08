package com.monstergym;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardTest extends ApiTestBase {

    /** Visita en un día y hora fijos (HH:MM). */
    private void visita(long memberId, LocalDate day, String hhmm) {
        jdbc.update("INSERT INTO visit (member_id, checked_in_at, method, status_at_entry, employee_id) "
                + "VALUES (?, ?, 'manual', 'activa', 1)", memberId, day + "T" + hhmm + ":00");
    }

    @Test
    void socios_porEstado() throws Exception {
        insertMember(1, "0001", "Activa Uno", null, 20);
        insertMember(2, "0002", "Activa Dos", null, 10);
        insertMember(3, "0003", "Por Vencer", null, 1);
        insertMember(4, "0004", "Vencida", null, -1);
        insertMember(5, "0005", "Sin Membresía", null, null);
        insertMember(6, "0006", "Dado De Baja", null, 20);
        jdbc.update("UPDATE member SET active = 0 WHERE id = 6");

        mvc.perform(get("/api/dashboard").session(loginAs("entrenador")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeMembers").value(3))  // activa + por vencer
                .andExpect(jsonPath("$.expiringCount").value(1))
                .andExpect(jsonPath("$.expiredCount").value(2))
                .andExpect(jsonPath("$.aforo.capacity").value(120));
    }

    @Test
    void visitas_hoyPorHoraYPico() throws Exception {
        insertMember(1, "0001", "Uno", null, 20);
        LocalDate hoy = today();
        visita(1, hoy, "00:00");  // antes de las 06:00: cuenta en el total, no en la gráfica
        visita(1, hoy, "06:15");
        visita(1, hoy, "06:40");
        visita(1, hoy, "18:05");
        visita(1, hoy.minusDays(1), "00:00");
        visita(1, hoy.minusDays(2), "10:00");

        mvc.perform(get("/api/dashboard").session(loginAs("recepcion")))
                .andExpect(jsonPath("$.visitsToday").value(4))
                .andExpect(jsonPath("$.visitsDeltaVsYesterday").value(3)) // ayer hasta esta hora: 1
                .andExpect(jsonPath("$.firstHour").value(6))
                .andExpect(jsonPath("$.visitsByHour.length()").value(16))
                .andExpect(jsonPath("$.visitsByHour[0]").value(2))
                .andExpect(jsonPath("$.visitsByHour[12]").value(1))
                .andExpect(jsonPath("$.aforo.peakHour").value("06:00"));
    }

    @Test
    void sinVisitas_noHayPico() throws Exception {
        mvc.perform(get("/api/dashboard").session(loginAs("recepcion")))
                .andExpect(jsonPath("$.visitsToday").value(0))
                .andExpect(jsonPath("$.aforo.peakHour").doesNotExist())
                .andExpect(jsonPath("$.recentVisits.length()").value(0));
    }

    @Test
    void entradasRecientes_lasUltimas6() throws Exception {
        insertMember(1, "0412", "Daniela Ortega", null, 20);
        LocalDate hoy = today().minusDays(1);
        for (int h = 10; h <= 17; h++) visita(1, hoy, h + ":00");
        mvc.perform(get("/api/dashboard").session(loginAs("recepcion")))
                .andExpect(jsonPath("$.recentVisits.length()").value(6))
                .andExpect(jsonPath("$.recentVisits[0].time").value("17:00"))
                .andExpect(jsonPath("$.recentVisits[0].name").value("Daniela Ortega"))
                .andExpect(jsonPath("$.recentVisits[0].status").value("activa"));
    }

    @Test
    void refriEIngresosDelDia_sinCancelados() throws Exception {
        insertMember(1, "0001", "Uno", null, 20); // su pago (id 1000) es de enero: no cuenta hoy
        String hoy = today().toString(), ayer = today().minusDays(1).toString();
        jdbc.update("INSERT INTO product (id, name, price_cents, stock, capacity, min_stock) VALUES "
                + "(1, 'Agua natural', 1500, 10, 20, 2), (2, 'Proteína lista', 5500, 10, 20, 2), "
                + "(3, 'Café frío', 4000, 10, 20, 2), (4, 'Jugo verde', 4500, 10, 20, 2)");
        jdbc.update("INSERT INTO shift (id, employee_id, opened_at, opening_cash_cents) VALUES (1, 2, ?, 0)",
                hoy + "T00:00:00");

        String pay = "INSERT INTO payment (member_id, plan_id, amount_cents, method, shift_id, employee_id, paid_at, voided_at) "
                + "VALUES (1, 3, ?, 'efectivo', 1, 2, ?, ?)";
        jdbc.update(pay, 30000, hoy + "T00:00:01", null);
        jdbc.update(pay, 12000, hoy + "T00:00:02", null);
        jdbc.update(pay, 30000, hoy + "T00:00:03", hoy + "T00:00:04"); // cancelado
        jdbc.update(pay, 99900, ayer + "T12:00:00", null);              // ayer

        String sale = "INSERT INTO sale (id, shift_id, employee_id, total_cents, method, created_at, voided_at) "
                + "VALUES (?, 1, 2, ?, 'efectivo', ?, ?)";
        jdbc.update(sale, 1, 4500, hoy + "T00:00:01", null);
        jdbc.update(sale, 2, 11000, hoy + "T00:00:02", null);
        jdbc.update(sale, 3, 4000, hoy + "T00:00:03", hoy + "T00:00:04"); // cancelada
        jdbc.update(sale, 4, 4500, ayer + "T12:00:00", null);              // ayer
        String item = "INSERT INTO sale_item (sale_id, product_id, qty, unit_price_cents) VALUES (?, ?, ?, ?)";
        jdbc.update(item, 1, 1, 3, 1500);
        jdbc.update(item, 2, 2, 2, 5500);
        jdbc.update(item, 3, 3, 1, 4000);
        jdbc.update(item, 4, 4, 1, 4500);

        mvc.perform(get("/api/dashboard").session(loginAs("recepcion")))
                .andExpect(jsonPath("$.income.membershipsCents").value(42000))
                .andExpect(jsonPath("$.income.fridgeCents").value(15500))
                .andExpect(jsonPath("$.income.totalCents").value(57500))
                .andExpect(jsonPath("$.topProducts[*].name", contains("Agua natural", "Proteína lista")))
                .andExpect(jsonPath("$.topProducts[0].sold").value(3));
    }
}

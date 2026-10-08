package com.monstergym;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.time.YearMonth;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SettingsAndReportsTest extends ApiTestBase {

    private org.springframework.test.web.servlet.ResultActions guardar(MockHttpSession s, Map<String, Object> data)
            throws Exception {
        return mvc.perform(put("/api/settings").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(data)));
    }

    // ── Configuración ──────────────────────────────────────────────────────────

    @Test
    void configuracion_leerYGuardarParcial() throws Exception {
        MockHttpSession s = loginAs("admin");
        mvc.perform(get("/api/settings").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gymName").value("Monster Gym"))
                .andExpect(jsonPath("$.capacity").value(120))
                .andExpect(jsonPath("$.warnDays").value(3))
                .andExpect(jsonPath("$.manualAccess").value(true))
                .andExpect(jsonPath("$.insideMinutes").value(120));

        guardar(s, Map.of("capacity", 80, "manualAccess", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(80))
                .andExpect(jsonPath("$.manualAccess").value(false))
                .andExpect(jsonPath("$.gymName").value("Monster Gym")); // lo demás no cambia
        mvc.perform(get("/api/dashboard").session(s)).andExpect(jsonPath("$.aforo.capacity").value(80));
    }

    @Test
    void configuracion_validaciones() throws Exception {
        MockHttpSession s = loginAs("admin");
        guardar(s, Map.of("capacity", 0)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.capacity").exists());
        guardar(s, Map.of("warnDays", 31)).andExpect(jsonPath("$.error.fields.warnDays").exists());
        guardar(s, Map.of("gymName", "  ")).andExpect(jsonPath("$.error.fields.gymName").exists());
        guardar(s, Map.of("insideMinutes", 5)).andExpect(jsonPath("$.error.fields.insideMinutes").exists());
    }

    @Test
    void configuracion_soloAdministrador() throws Exception {
        mvc.perform(get("/api/settings").session(loginAs("recepcion"))).andExpect(status().isForbidden());
        guardar(loginAs("entrenador"), Map.of("capacity", 50)).andExpect(status().isForbidden());
    }

    @Test
    void diasDeAviso_cambianElEstadoPorVencer() throws Exception {
        insertMember(1, "0001", "Socio", null, 5);
        MockHttpSession s = loginAs("admin");
        mvc.perform(get("/api/members/1").session(s)).andExpect(jsonPath("$.status").value("activa"));
        guardar(s, Map.of("warnDays", 7)).andExpect(status().isOk());
        mvc.perform(get("/api/members/1").session(s)).andExpect(jsonPath("$.status").value("por_vencer"));
    }

    // ── Reportes ───────────────────────────────────────────────────────────────

    @Test
    void ingresosPorMes_membresiasMasRefriSinCancelados() throws Exception {
        insertMember(1, "0001", "Socio", null, null);
        YearMonth now = YearMonth.now(MX), prev = now.minusMonths(1);
        jdbc.update("INSERT INTO shift (id, employee_id, opened_at, opening_cash_cents) VALUES (1, 2, '2026-01-01T08:00:00', 0)");
        String pay = "INSERT INTO payment (member_id, plan_id, amount_cents, method, shift_id, employee_id, paid_at, voided_at) "
                + "VALUES (1, 3, ?, 'efectivo', 1, 2, ?, ?)";
        jdbc.update(pay, 30000, now.atDay(1) + "T10:00:00", null);
        jdbc.update(pay, 99999, now.atDay(1) + "T11:00:00", now.atDay(1) + "T11:05:00"); // cancelado
        jdbc.update(pay, 12000, prev.atDay(15) + "T10:00:00", null);
        jdbc.update("INSERT INTO sale (shift_id, employee_id, total_cents, method, created_at) VALUES (1, 2, 4500, 'efectivo', ?)",
                now.atDay(1) + "T12:00:00");

        mvc.perform(get("/api/reports/income-monthly?months=3").session(loginAs("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].month").value(now.toString()))
                .andExpect(jsonPath("$[2].current").value(true))
                .andExpect(jsonPath("$[2].membershipsCents").value(30000))
                .andExpect(jsonPath("$[2].fridgeCents").value(4500))
                .andExpect(jsonPath("$[2].totalCents").value(34500))
                .andExpect(jsonPath("$[1].totalCents").value(12000))
                .andExpect(jsonPath("$[0].totalCents").value(0));
    }

    @Test
    void resumen_activosAltasYTasaDeRenovacion() throws Exception {
        insertMember(1, "0001", "Renovó", null, -10);
        addMembership(1, 20);                          // venció hace 10 días y ya pagó otra
        insertMember(2, "0002", "No Renovó", null, -5);
        insertMember(3, "0003", "Hace Mucho", null, -40); // fuera de la ventana de 30 días
        insertMember(4, "0004", "Vigente", null, 15);
        jdbc.update("UPDATE member SET joined_on = ? WHERE id = 4", today().withDayOfMonth(1).toString());

        mvc.perform(get("/api/reports/summary").session(loginAs("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeMembers").value(2))
                .andExpect(jsonPath("$.newThisMonth").value(1))
                .andExpect(jsonPath("$.expiredLast30Days").value(2))
                .andExpect(jsonPath("$.renewalRate").value(50));
    }

    @Test
    void reportes_soloAdministrador() throws Exception {
        mvc.perform(get("/api/reports/income-monthly").session(loginAs("recepcion"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/summary").session(loginAs("entrenador"))).andExpect(status().isForbidden());
    }
}

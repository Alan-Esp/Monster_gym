package com.monstergym;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentTest extends ApiTestBase {

    private static final long MENSUAL = 3, SEMANAL = 1;
    private LocalDate hoy;

    @BeforeEach
    void setUpMember() {
        hoy = LocalDate.now(ZoneId.of("America/Mexico_City"));
        jdbc.update("INSERT INTO member (id, number, full_name, joined_on) VALUES (1, '0001', 'Socio Uno', '2026-01-01')");
    }

    private MockHttpSession recepcionConTurno() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        mvc.perform(post("/api/shifts/open").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("openingCashCents", 0))))
                .andExpect(status().isCreated());
        return s;
    }

    private ResultActions charge(MockHttpSession s, long memberId, long planId, String method) throws Exception {
        return mvc.perform(post("/api/payments").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("memberId", memberId, "planId", planId, "method", method))));
    }

    /** Membresía previa del socio 1 que termina en hoy + offset días (pago en un turno viejo). */
    private void membresiaPrevia(int offsetDias) {
        jdbc.update("INSERT OR IGNORE INTO shift (id, employee_id, opened_at, closed_at, opening_cash_cents) "
                + "VALUES (900, 1, '2026-01-01T08:00:00', '2026-01-01T20:00:00', 0)");
        jdbc.update("INSERT INTO payment (id, member_id, plan_id, amount_cents, method, shift_id, employee_id, paid_at) "
                + "VALUES (900, 1, 3, 30000, 'efectivo', 900, 1, '2026-01-01T09:00:00')");
        LocalDate fin = hoy.plusDays(offsetDias);
        jdbc.update("INSERT INTO membership (member_id, plan_id, payment_id, starts_on, ends_on) VALUES (1, 3, 900, ?, ?)",
                fin.minusDays(29).toString(), fin.toString());
    }

    // ── Cobro y renovación ─────────────────────────────────────────────────────

    @Test
    void cobro_sinTurno_devuelveSinTurnoYNoGuardaNada() throws Exception {
        charge(loginAs("recepcion"), 1, MENSUAL, "efectivo")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SIN_TURNO"))
                .andExpect(jsonPath("$.error.message").value("Abre un turno antes de cobrar."));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM membership", Integer.class)).isZero();
    }

    @Test
    void cobro_sinMembresia_empiezaHoyYDevuelveDatosDelTicket() throws Exception {
        charge(recepcionConTurno(), 1, MENSUAL, "efectivo")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.folio").value(org.hamcrest.Matchers.matchesPattern("\\d{6}")))
                .andExpect(jsonPath("$.member.number").value("0001"))
                .andExpect(jsonPath("$.member.fullName").value("Socio Uno"))
                .andExpect(jsonPath("$.plan.name").value("Mensual"))
                .andExpect(jsonPath("$.startsOn").value(hoy.toString()))
                .andExpect(jsonPath("$.endsOn").value(hoy.plusDays(30).toString()))
                .andExpect(jsonPath("$.amountCents").value(30000))
                .andExpect(jsonPath("$.method").value("efectivo"))
                .andExpect(jsonPath("$.employeeName").value("Rocío Recepción"))
                .andExpect(jsonPath("$.paidAt").isString());
        assertThat(jdbc.queryForObject("SELECT status FROM v_member_status WHERE member_id = 1", String.class))
                .isEqualTo("activa");
    }

    @Test
    void renovacion_vigente_sumaDesdeElVencimiento() throws Exception {
        membresiaPrevia(10);
        charge(recepcionConTurno(), 1, SEMANAL, "tarjeta")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startsOn").value(hoy.plusDays(11).toString()))
                .andExpect(jsonPath("$.endsOn").value(hoy.plusDays(18).toString()));
    }

    @Test
    void renovacion_venceHoy_empiezaManana() throws Exception {
        membresiaPrevia(0);
        charge(recepcionConTurno(), 1, SEMANAL, "efectivo")
                .andExpect(jsonPath("$.startsOn").value(hoy.plusDays(1).toString()));
    }

    @Test
    void renovacion_vencida_cuentaDesdeHoy() throws Exception {
        membresiaPrevia(-3);
        charge(recepcionConTurno(), 1, SEMANAL, "transferencia")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startsOn").value(hoy.toString()))
                .andExpect(jsonPath("$.endsOn").value(hoy.plusDays(7).toString()));
    }

    @Test
    void elPrecioCobradoEsElDelMomento() throws Exception {
        MockHttpSession s = recepcionConTurno();
        charge(s, 1, MENSUAL, "efectivo").andExpect(jsonPath("$.amountCents").value(30000));
        mvc.perform(put("/api/plans/3").session(loginAs("admin")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("priceCents", 35000))))
                .andExpect(status().isOk());
        charge(s, 1, MENSUAL, "efectivo").andExpect(jsonPath("$.amountCents").value(35000));
        assertThat(jdbc.queryForList("SELECT amount_cents FROM payment ORDER BY id", Long.class))
                .containsExactly(30000L, 35000L);
    }

    // ── Validaciones y permisos ───────────────────────────────────────────────

    @Test
    void cobro_datosInvalidos() throws Exception {
        MockHttpSession s = recepcionConTurno();
        charge(s, 1, MENSUAL, "cheque")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.method").exists());
        charge(s, 1, 99, "efectivo")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.planId").value("El plan elegido no existe."));
        charge(s, 999, MENSUAL, "efectivo")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ENCONTRADO"));
    }

    @Test
    void entrenador_noPuedeCobrar() throws Exception {
        charge(loginAs("entrenador"), 1, MENSUAL, "efectivo").andExpect(status().isForbidden());
    }

    @Test
    void pagosDelTurno_soloLosDelTurnoActual() throws Exception {
        membresiaPrevia(-30); // pago 900, de otro turno
        MockHttpSession s = recepcionConTurno();
        charge(s, 1, MENSUAL, "efectivo");
        mvc.perform(get("/api/payments?shift=current").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].member.fullName").value("Socio Uno"));
    }

    // ── Cancelación ────────────────────────────────────────────────────────────

    @Test
    void cancelar_pagoDejaDeContarEnLaVigencia() throws Exception {
        MockHttpSession s = recepcionConTurno();
        charge(s, 1, MENSUAL, "efectivo");
        long id = jdbc.queryForObject("SELECT id FROM payment", Long.class);

        mvc.perform(post("/api/payments/" + id + "/void").session(loginAs("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("reason", "Cobro duplicado"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voidedAt").isString())
                .andExpect(jsonPath("$.voidReason").value("Cobro duplicado"));

        assertThat(jdbc.queryForObject("SELECT status FROM v_member_status WHERE member_id = 1", String.class))
                .isEqualTo("vencida");
        // El siguiente cobro vuelve a contar desde hoy
        charge(s, 1, SEMANAL, "efectivo").andExpect(jsonPath("$.startsOn").value(hoy.toString()));
    }

    @Test
    void cancelar_reglas() throws Exception {
        MockHttpSession s = recepcionConTurno();
        charge(s, 1, MENSUAL, "efectivo");
        long id = jdbc.queryForObject("SELECT id FROM payment", Long.class);
        MockHttpSession admin = loginAs("admin");
        String url = "/api/payments/" + id + "/void";

        mvc.perform(post(url).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("reason", "x")))).andExpect(status().isForbidden());
        mvc.perform(post(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("reason", " ")))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("reason", "Error")))).andExpect(status().isOk());
        mvc.perform(post(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("reason", "Otra vez"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PAGO_CANCELADO"));
    }

    @Test
    void cancelar_pagoDeTurnoCerrado_noSePermite() throws Exception {
        membresiaPrevia(5); // pago 900 en el turno 900, ya cerrado
        mvc.perform(post("/api/payments/900/void").session(loginAs("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("reason", "Error"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TURNO_CERRADO"));
    }
}

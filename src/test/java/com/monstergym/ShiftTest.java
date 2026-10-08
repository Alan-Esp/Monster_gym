package com.monstergym;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShiftTest extends ApiTestBase {

    private ResultActions open(MockHttpSession s, Object cents) throws Exception {
        return mvc.perform(post("/api/shifts/open").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(cents == null ? Map.of() : Map.of("openingCashCents", cents))));
    }

    private ResultActions close(MockHttpSession s, long cents) throws Exception {
        return mvc.perform(post("/api/shifts/close").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("countedCashCents", cents))));
    }

    @Test
    void abrirTurno_loRegistraYApareceEnMe() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        open(s, 50000)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeName").value("Rocío Recepción"))
                .andExpect(jsonPath("$.openingCashCents").value(50000))
                .andExpect(jsonPath("$.expectedCashCents").value(50000))
                .andExpect(jsonPath("$.closedAt").doesNotExist());

        mvc.perform(get("/api/me").session(s))
                .andExpect(jsonPath("$.shift.id").isNumber())
                .andExpect(jsonPath("$.shift.openedAt").value(org.hamcrest.Matchers.matchesPattern(
                        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")));
    }

    @Test
    void soloUnTurnoAbiertoPorEmpleado() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        open(s, 50000).andExpect(status().isCreated());
        open(s, 10000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TURNO_ABIERTO"));

        // Otro empleado sí puede abrir el suyo
        open(loginAs("admin"), 0).andExpect(status().isCreated());
    }

    @Test
    void fondoInvalido_devuelve422() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        open(s, -1)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.openingCashCents").exists());
        open(s, null).andExpect(status().isUnprocessableEntity());
        open(s, "abc").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void turnoActual_sinTurno_devuelveSinTurno() throws Exception {
        mvc.perform(get("/api/shifts/current").session(loginAs("recepcion")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SIN_TURNO"));
    }

    @Test
    void cerrar_sinTurno_devuelveSinTurno() throws Exception {
        close(loginAs("recepcion"), 0)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SIN_TURNO"));
    }

    @Test
    void corte_calculaEsperadoContadoYDiferencia() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        open(s, 50000).andExpect(status().isCreated());
        long shiftId = jdbc.queryForObject("SELECT id FROM shift WHERE employee_id = 2", Long.class);
        insertMovements(shiftId);

        mvc.perform(get("/api/shifts/current").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships.count").value(3))
                .andExpect(jsonPath("$.memberships.totalCents").value(105000))
                .andExpect(jsonPath("$.memberships.byMethod.efectivo").value(60000))
                .andExpect(jsonPath("$.memberships.byPlan[0].plan").value("Semanal"))
                .andExpect(jsonPath("$.memberships.byPlan[0].count").value(1))
                .andExpect(jsonPath("$.memberships.byPlan[1].plan").value("Mensual"))
                .andExpect(jsonPath("$.memberships.byPlan[1].count").value(2))
                .andExpect(jsonPath("$.memberships.byPlan[1].totalCents").value(90000))
                .andExpect(jsonPath("$.fridge.salesCount").value(2))
                .andExpect(jsonPath("$.fridge.units").value(3))
                .andExpect(jsonPath("$.fridge.totalCents").value(8500))
                .andExpect(jsonPath("$.fridge.byMethod.efectivo").value(3000))
                .andExpect(jsonPath("$.fridge.byMethod.tarjeta").value(5500))
                .andExpect(jsonPath("$.byMethod.efectivo").value(63000))
                .andExpect(jsonPath("$.byMethod.tarjeta").value(50500))
                .andExpect(jsonPath("$.byMethod.transferencia").value(0))
                .andExpect(jsonPath("$.totalCents").value(113500))
                // 500 de fondo + 600 de membresías en efectivo + 30 de refri en efectivo
                .andExpect(jsonPath("$.expectedCashCents").value(113000))
                .andExpect(jsonPath("$.countedCashCents").doesNotExist());

        close(s, 112000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").isString())
                .andExpect(jsonPath("$.expectedCashCents").value(113000))
                .andExpect(jsonPath("$.countedCashCents").value(112000))
                .andExpect(jsonPath("$.differenceCents").value(-1000));
    }

    @Test
    void turnoCerrado_noSeModificaYSePuedeAbrirOtro() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        open(s, 50000);
        close(s, 50000).andExpect(status().isOk());
        String closedAt = jdbc.queryForObject("SELECT closed_at FROM shift WHERE employee_id = 2", String.class);

        close(s, 99999).andExpect(status().isConflict());
        mvc.perform(get("/api/shifts/current").session(s)).andExpect(status().isConflict());
        mvc.perform(get("/api/me").session(s)).andExpect(jsonPath("$.shift").doesNotExist());

        open(s, 20000).andExpect(status().isCreated());
        mvc.perform(get("/api/shifts").session(loginAs("admin")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].closedAt").value(closedAt))
                .andExpect(jsonPath("$[1].countedCashCents").value(50000))
                .andExpect(jsonPath("$[1].differenceCents").value(0));
    }

    /**
     * Turno con: Mensual efectivo $450, Mensual tarjeta $450, Semanal efectivo $150, un pago cancelado,
     * venta efectivo $30 (2 aguas), venta tarjeta $55 (1 proteína), una venta cancelada
     * y un pago de otro turno que no debe contar.
     */
    private void insertMovements(long shiftId) {
        jdbc.update("INSERT INTO plan (id, name, days, price_cents) VALUES (1, 'Semanal', 7, 15000), "
                + "(3, 'Mensual', 30, 45000)");
        jdbc.update("INSERT INTO member (id, number, full_name, joined_on) VALUES (1, '0001', 'Socio Uno', '2026-01-01')");
        jdbc.update("INSERT INTO product (id, name, price_cents, stock, capacity, min_stock) VALUES "
                + "(1, 'Agua', 1500, 10, 20, 2), (2, 'Proteína', 5500, 10, 20, 2)");
        jdbc.update("INSERT INTO shift (id, employee_id, opened_at, opening_cash_cents) "
                + "VALUES (999, 1, '2026-01-01T08:00:00', 0)");

        String pay = "INSERT INTO payment (member_id, plan_id, amount_cents, method, shift_id, employee_id, paid_at, voided_at) "
                + "VALUES (1, ?, ?, ?, ?, 2, '2026-10-07T09:00:00', ?)";
        jdbc.update(pay, 3, 45000, "efectivo", shiftId, null);
        jdbc.update(pay, 3, 45000, "tarjeta", shiftId, null);
        jdbc.update(pay, 1, 15000, "efectivo", shiftId, null);
        jdbc.update(pay, 3, 45000, "efectivo", shiftId, "2026-10-07T10:00:00");
        jdbc.update(pay, 3, 45000, "efectivo", 999L, null);

        String sale = "INSERT INTO sale (id, shift_id, employee_id, total_cents, method, created_at, voided_at) "
                + "VALUES (?, ?, 2, ?, ?, '2026-10-07T11:00:00', ?)";
        jdbc.update(sale, 1, shiftId, 3000, "efectivo", null);
        jdbc.update(sale, 2, shiftId, 5500, "tarjeta", null);
        jdbc.update(sale, 3, shiftId, 1500, "efectivo", "2026-10-07T11:30:00");
        String item = "INSERT INTO sale_item (sale_id, product_id, qty, unit_price_cents) VALUES (?, ?, ?, ?)";
        jdbc.update(item, 1, 1, 2, 1500);
        jdbc.update(item, 2, 2, 1, 5500);
        jdbc.update(item, 3, 1, 1, 1500);
    }
}

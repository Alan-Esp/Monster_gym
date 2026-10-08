package com.monstergym;

import com.monstergym.common.Times;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VisitTest extends ApiTestBase {

    private ResultActions entrada(MockHttpSession s, long memberId, Boolean confirm) throws Exception {
        Map<String, Object> data = new HashMap<>(Map.of("memberId", memberId, "method", "manual"));
        if (confirm != null) data.put("confirm", confirm);
        return mvc.perform(post("/api/visits").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(data)));
    }

    private void visita(long memberId, LocalDateTime at, String method) {
        jdbc.update("INSERT INTO visit (member_id, checked_in_at, method, status_at_entry, employee_id) "
                + "VALUES (?, ?, ?, 'activa', 1)", memberId, Times.format(at), method);
    }

    private int visitas() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM visit", Integer.class);
    }

    // ── Registro ───────────────────────────────────────────────────────────────

    @Test
    void socioActivo_registraEntradaManual() throws Exception {
        insertMember(1, "0412", "Daniela Ortega", null, 21);
        entrada(loginAs("recepcion"), 1, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("manual"))
                .andExpect(jsonPath("$.statusAtEntry").value("activa"))
                .andExpect(jsonPath("$.member.fullName").value("Daniela Ortega"))
                .andExpect(jsonPath("$.member.validUntil").value(today().plusDays(21).toString()))
                .andExpect(jsonPath("$.member.daysLeft").value(21));
        assertThat(jdbc.queryForObject("SELECT employee_id FROM visit", Long.class)).isEqualTo(2L);
    }

    @Test
    void socioPorVencer_entraSinConfirmacion() throws Exception {
        insertMember(1, "0001", "Socio Por Vencer", null, 2);
        entrada(loginAs("recepcion"), 1, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusAtEntry").value("por_vencer"));
    }

    @Test
    void socioVencido_exigeConfirmacionYQuedaMarcado() throws Exception {
        insertMember(1, "0398", "Jorge Medina", null, -2);
        MockHttpSession s = loginAs("recepcion");
        entrada(s, 1, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOCIO_VENCIDO"))
                .andExpect(jsonPath("$.error.message").value(containsString("La membresía de Jorge Medina venció el")))
                .andExpect(jsonPath("$.error.message").value(containsString("¿Registrar la entrada de todos modos?")));
        entrada(s, 1, false).andExpect(status().isConflict());
        assertThat(visitas()).isZero();

        entrada(s, 1, true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusAtEntry").value("vencida"));
        assertThat(jdbc.queryForObject("SELECT status_at_entry FROM visit", String.class)).isEqualTo("vencida");
    }

    @Test
    void socioSinMembresia_cuentaComoVencido() throws Exception {
        insertMember(1, "0500", "Hugo Navarro", null, null);
        entrada(loginAs("recepcion"), 1, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOCIO_VENCIDO"))
                .andExpect(jsonPath("$.error.message").value(containsString("no tiene ninguna membresía")));
    }

    @Test
    void dobleRegistro_menosDe5Minutos_exigeConfirmacion() throws Exception {
        insertMember(1, "0412", "Daniela Ortega", null, 21);
        visita(1, now().minusMinutes(4), "huella");
        MockHttpSession s = loginAs("recepcion");
        entrada(s, 1, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOBLE_REGISTRO"))
                .andExpect(jsonPath("$.error.message").value(containsString("hace 4 minutos")));
        assertThat(visitas()).isEqualTo(1);

        entrada(s, 1, true).andExpect(status().isCreated());
        assertThat(visitas()).isEqualTo(2);
        // La que acaba de registrarse también cuenta: otra entrada inmediata vuelve a pedir confirmación
        entrada(s, 1, null).andExpect(jsonPath("$.error.code").value("DOBLE_REGISTRO"));
    }

    @Test
    void dobleRegistro_masDe5Minutos_noPideConfirmacion() throws Exception {
        insertMember(1, "0412", "Daniela Ortega", null, 21);
        visita(1, now().minusMinutes(6), "manual");
        entrada(loginAs("recepcion"), 1, null).andExpect(status().isCreated());
    }

    @Test
    void dobleRegistro_unaVisitaConFechaFuturaNoOcultaLaReciente() throws Exception {
        // Puede pasar si el reloj de la PC se adelantó y luego se corrigió
        insertMember(1, "0412", "Daniela Ortega", null, 21);
        visita(1, now().plusHours(3), "huella");
        visita(1, now().minusMinutes(2), "manual");
        entrada(loginAs("recepcion"), 1, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DOBLE_REGISTRO"));
    }

    @Test
    void dobleRegistro_esPorSocio() throws Exception {
        insertMember(1, "0412", "Daniela Ortega", null, 21);
        insertMember(2, "0087", "Luis Cruz", null, 21);
        visita(1, now().minusMinutes(1), "manual");
        entrada(loginAs("recepcion"), 2, null).andExpect(status().isCreated());
    }

    @Test
    void vencidoYDobleRegistro_unaSolaConfirmacionCubreAmbos() throws Exception {
        insertMember(1, "0398", "Jorge Medina", null, -2);
        visita(1, now().minusMinutes(2), "manual");
        MockHttpSession s = loginAs("recepcion");
        entrada(s, 1, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SOCIO_VENCIDO"))
                .andExpect(jsonPath("$.error.message").value(containsString("venció el")))
                .andExpect(jsonPath("$.error.message").value(containsString("ya registró entrada")));
        entrada(s, 1, true).andExpect(status().isCreated());
    }

    @Test
    void accesoManualDesactivado_noRegistra() throws Exception {
        insertMember(1, "0412", "Daniela Ortega", null, 21);
        jdbc.update("UPDATE setting SET value = '0' WHERE key = 'manual_access'");
        MockHttpSession s = loginAs("recepcion");
        entrada(s, 1, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ACCESO_MANUAL_DESACTIVADO"));
        mvc.perform(get("/api/visits").session(s)).andExpect(jsonPath("$.manualAccess").value(false));
        assertThat(visitas()).isZero();
    }

    @Test
    void validacionesYPermisos() throws Exception {
        insertMember(1, "0412", "Daniela Ortega", null, 21);
        entrada(loginAs("entrenador"), 1, null).andExpect(status().isForbidden());
        MockHttpSession s = loginAs("recepcion");
        entrada(s, 999, null).andExpect(status().isNotFound());
        mvc.perform(post("/api/visits").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("memberId", 1, "method", "huella"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.method").exists());
        assertThat(visitas()).isZero();
    }

    // ── Aforo y bitácora ───────────────────────────────────────────────────────

    @Test
    void aforo_cuentaVisitasDeLosUltimosNMinutos() throws Exception {
        insertMember(1, "0001", "Uno", null, 21);
        insertMember(2, "0002", "Dos", null, 21);
        insertMember(3, "0003", "Tres", null, 21);
        insertMember(4, "0004", "Cuatro", null, 21);
        LocalDateTime now = now();
        visita(1, now.minusMinutes(1), "huella");
        visita(2, now.minusMinutes(119), "huella");
        visita(3, now.minusMinutes(121), "manual"); // ya salió de la ventana de 120 min
        visita(4, now.minusMinutes(170), "manual");
        MockHttpSession s = loginAs("entrenador");

        mvc.perform(get("/api/visits").session(s)).andExpect(jsonPath("$.insideNow").value(2));
        mvc.perform(get("/api/dashboard").session(s)).andExpect(jsonPath("$.aforo.inside").value(2));

        jdbc.update("UPDATE setting SET value = '180' WHERE key = 'inside_minutes'");
        mvc.perform(get("/api/dashboard").session(s)).andExpect(jsonPath("$.aforo.inside").value(4));
    }

    @Test
    void bitacoraDelDia() throws Exception {
        insertMember(1, "0412", "Daniela Ortega", null, 21);
        insertMember(2, "0087", "Luis Cruz", null, 21);
        visita(1, LocalDateTime.of(2026, 10, 5, 7, 10), "huella");  // lunes
        visita(2, LocalDateTime.of(2026, 10, 7, 9, 0), "huella");
        visita(1, LocalDateTime.of(2026, 10, 7, 18, 30), "manual");
        visita(2, LocalDateTime.of(2026, 10, 7, 19, 45), "huella");
        visita(1, LocalDateTime.of(2026, 10, 8, 8, 0), "huella");   // otro día

        mvc.perform(get("/api/visits").param("date", "2026-10-07").session(loginAs("recepcion")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-07"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].time").value("19:45"))
                .andExpect(jsonPath("$.items[0].name").value("Luis Cruz"))
                .andExpect(jsonPath("$.items[0].number").value("0087"))
                .andExpect(jsonPath("$.items[2].time").value("09:00"))
                .andExpect(jsonPath("$.fingerprintPct").value(67))
                .andExpect(jsonPath("$.weekCount").value(4))
                .andExpect(jsonPath("$.manualAccess").value(true));

        mvc.perform(get("/api/visits").param("date", "07/10/2026").session(loginAs("recepcion")))
                .andExpect(status().isUnprocessableEntity());
    }
}

package com.monstergym;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthTest extends ApiTestBase {

    @Test
    void loginCorrecto_devuelveDatosDelEmpleado() throws Exception {
        assertThat(postLogin("admin", PIN).getResponse().getStatus()).isEqualTo(200);

        mvc.perform(get("/api/me").session(loginAs("recepcion")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Rocío Recepción"))
                .andExpect(jsonPath("$.initials").value("RR"))
                .andExpect(jsonPath("$.role").value("recepcion"))
                .andExpect(jsonPath("$.shift").doesNotExist());
    }

    @Test
    void me_incluyeElTurnoAbierto() throws Exception {
        jdbc.update("INSERT INTO shift (id, employee_id, opened_at, opening_cash_cents) "
                + "VALUES (7, 2, '2026-10-07T08:00:00', 50000)");
        mvc.perform(get("/api/me").session(loginAs("recepcion")))
                .andExpect(jsonPath("$.shift.id").value(7))
                .andExpect(jsonPath("$.shift.openedAt").value("2026-10-07T08:00:00"));
    }

    @Test
    void pinIncorrecto_devuelve401() throws Exception {
        var res = postLogin("admin", "9999").getResponse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("NO_AUTENTICADO");
        assertThat(failedAttempts("admin")).isEqualTo(1);
    }

    @Test
    void usuarioInexistente_devuelve401() throws Exception {
        assertThat(postLogin("nadie", PIN).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void usuarioInactivo_noPuedeEntrar() throws Exception {
        assertThat(postLogin("inactiva", PIN).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void datosVacios_devuelve422() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "", "pin", ""))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDACION"))
                .andExpect(jsonPath("$.error.fields.username").exists())
                .andExpect(jsonPath("$.error.fields.pin").exists());
    }

    @Test
    void cincoIntentosFallidos_bloqueanLaCuenta() throws Exception {
        for (int i = 0; i < 4; i++) {
            assertThat(postLogin("admin", "0000").getResponse().getStatus()).isEqualTo(401);
        }
        var fifth = postLogin("admin", "0000").getResponse();
        assertThat(fifth.getStatus()).isEqualTo(423);
        assertThat(fifth.getContentAsString()).contains("CUENTA_BLOQUEADA");

        // Bloqueada: ni el PIN correcto entra
        var blocked = postLogin("admin", PIN).getResponse();
        assertThat(blocked.getStatus()).isEqualTo(423);
        assertThat(blocked.getContentAsString()).contains("15 minutos");
    }

    @Test
    void bloqueoVencido_permiteEntrarYReiniciaContador() throws Exception {
        jdbc.update("UPDATE employee SET failed_attempts = 5, locked_until = '2000-01-01T00:00:00' "
                + "WHERE username = 'admin'");
        assertThat(postLogin("admin", PIN).getResponse().getStatus()).isEqualTo(200);
        assertThat(failedAttempts("admin")).isZero();
        assertThat(jdbc.queryForObject("SELECT locked_until FROM employee WHERE username = 'admin'", String.class))
                .isNull();
    }

    @Test
    void bloqueoVencido_elConteoEmpiezaDeNuevo() throws Exception {
        jdbc.update("UPDATE employee SET failed_attempts = 5, locked_until = '2000-01-01T00:00:00' "
                + "WHERE username = 'admin'");
        assertThat(postLogin("admin", "0000").getResponse().getStatus()).isEqualTo(401);
        assertThat(failedAttempts("admin")).isEqualTo(1);
    }

    @Test
    void loginCorrecto_reiniciaIntentosFallidos() throws Exception {
        for (int i = 0; i < 4; i++) postLogin("admin", "0000");
        assertThat(postLogin("admin", PIN).getResponse().getStatus()).isEqualTo(200);
        // Cuatro fallos más no bloquean porque el contador volvió a cero
        for (int i = 0; i < 4; i++) {
            assertThat(postLogin("admin", "0000").getResponse().getStatus()).isEqualTo(401);
        }
        assertThat(postLogin("admin", PIN).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void elPinSeGuardaConBCrypt() {
        String hash = jdbc.queryForObject("SELECT pin_hash FROM employee WHERE username = 'admin'", String.class);
        assertThat(hash).startsWith("$2").isNotEqualTo(PIN);
    }

    private int failedAttempts(String username) {
        return jdbc.queryForObject("SELECT failed_attempts FROM employee WHERE username = ?", Integer.class, username);
    }
}

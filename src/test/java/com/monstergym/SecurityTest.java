package com.monstergym;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityTest extends ApiTestBase {

    // ── 401 sin sesión ─────────────────────────────────────────────────────────

    @Test
    void sinSesion_me_devuelve401() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("NO_AUTENTICADO"))
                .andExpect(jsonPath("$.error.message").value("Inicia sesión para continuar."));
    }

    @Test
    void sinSesion_turnoActual_devuelve401() throws Exception {
        mvc.perform(get("/api/shifts/current"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("NO_AUTENTICADO"));
    }

    @Test
    void sinSesion_abrirTurno_devuelve401() throws Exception {
        mvc.perform(post("/api/shifts/open").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("openingCashCents", 50000))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void archivosEstaticos_sonPublicos() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
        mvc.perform(get("/js/app.js")).andExpect(status().isOk());
    }

    // ── 403 por rol ────────────────────────────────────────────────────────────

    @Test
    void entrenador_abrirTurno_devuelve403() throws Exception {
        MockHttpSession session = loginAs("entrenador");
        mvc.perform(post("/api/shifts/open").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("openingCashCents", 50000))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SIN_PERMISO"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shift", Integer.class)).isZero();
    }

    @Test
    void entrenador_turnoActualYCierre_devuelven403() throws Exception {
        MockHttpSession session = loginAs("entrenador");
        mvc.perform(get("/api/shifts/current").session(session))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/shifts/close").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("countedCashCents", 0))))
                .andExpect(status().isForbidden());
    }

    @Test
    void recepcion_historialDeTurnos_devuelve403() throws Exception {
        mvc.perform(get("/api/shifts").session(loginAs("recepcion")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SIN_PERMISO"));
    }

    @Test
    void administrador_historialDeTurnos_devuelve200() throws Exception {
        mvc.perform(get("/api/shifts").session(loginAs("admin")))
                .andExpect(status().isOk());
    }

    @Test
    void entrenador_puedeConsultarMe() throws Exception {
        mvc.perform(get("/api/me").session(loginAs("entrenador")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("entrenador"));
    }

    // ── CSRF ───────────────────────────────────────────────────────────────────

    @Test
    void postSinTokenCsrf_devuelve403() throws Exception {
        MockHttpSession session = loginAs("recepcion");
        mvc.perform(post("/api/shifts/open").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("openingCashCents", 50000))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_INVALIDO"));
    }

    @Test
    void loginSinTokenCsrf_devuelve403() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "admin", "pin", PIN))))
                .andExpect(status().isForbidden());
    }

    // ── Sesión ─────────────────────────────────────────────────────────────────

    @Test
    void logout_invalidaLaSesion() throws Exception {
        MockHttpSession session = loginAs("admin");
        mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }
}

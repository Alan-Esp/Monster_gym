package com.monstergym;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo real del navegador: la página entrega la cookie XSRF-TOKEN y api.js la devuelve en X-XSRF-TOKEN.
 * Usa un contexto propio porque csrf() de spring-security-test reemplaza el repositorio de tokens del filtro.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CsrfCookieFlowTest extends ApiTestBase {

    private Cookie xsrfCookie() throws Exception {
        Cookie xsrf = mvc.perform(get("/index.html")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        return xsrf;
    }

    @Test
    void cookieXsrf_esLegibleDesdeJs() throws Exception {
        assertThat(xsrfCookie().isHttpOnly()).isFalse();
    }

    @Test
    void cookieXsrf_enCabecera_permiteLogin() throws Exception {
        Cookie xsrf = xsrfCookie();
        mvc.perform(post("/api/auth/login").cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "admin", "pin", PIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("administrador"));
    }

    @Test
    void cabeceraXsrfIncorrecta_devuelve403() throws Exception {
        mvc.perform(post("/api/auth/login").cookie(xsrfCookie()).header("X-XSRF-TOKEN", "otro-valor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", "admin", "pin", PIN))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("CSRF_INVALIDO"));
    }
}

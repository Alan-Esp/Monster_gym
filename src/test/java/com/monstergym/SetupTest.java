package com.monstergym;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Primer arranque en una PC nueva: sin administrador, la app pide crearlo. */
class SetupTest extends ApiTestBase {

    private void sinAdministradores() {
        jdbc.update("DELETE FROM employee WHERE role = 'administrador'");
    }

    private ResultActions crear(Map<String, Object> data) throws Exception {
        return mvc.perform(post("/api/setup/admin").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(data)));
    }

    @Test
    void conAdministrador_noPideConfiguracionNiDejaCrearOtro() throws Exception {
        mvc.perform(get("/api/setup")).andExpect(status().isOk()).andExpect(jsonPath("$.needsAdmin").value(false));
        crear(Map.of("name", "Intruso", "username", "intruso", "pin", "9999"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("YA_CONFIGURADO"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE username = 'intruso'", Integer.class))
                .isZero();
    }

    @Test
    void sinAdministrador_seCreaEIniciaSesion() throws Exception {
        sinAdministradores();
        mvc.perform(get("/api/setup")).andExpect(jsonPath("$.needsAdmin").value(true));

        MvcResult r = crear(Map.of("name", "Dueño del Gym", "username", " dueno ", "pin", "4321"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dueño del Gym"))
                .andExpect(jsonPath("$.role").value("administrador"))
                .andReturn();
        MockHttpSession s = (MockHttpSession) r.getRequest().getSession(false);
        mvc.perform(get("/api/employees").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/setup")).andExpect(jsonPath("$.needsAdmin").value(false));
        assertThat(postLogin("dueno", "4321").getResponse().getStatus()).isEqualTo(200);
        // Solo una vez
        crear(Map.of("name", "Otro", "username", "otro", "pin", "1111")).andExpect(status().isConflict());
    }

    @Test
    void validaLosDatos() throws Exception {
        sinAdministradores();
        crear(Map.of("name", "", "username", "x", "pin", "12"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.name").exists())
                .andExpect(jsonPath("$.error.fields.username").exists())
                .andExpect(jsonPath("$.error.fields.pin").exists());
        mvc.perform(get("/api/setup")).andExpect(jsonPath("$.needsAdmin").value(true));
    }

    @Test
    void usuarioYaUsadoPorOtroEmpleado() throws Exception {
        sinAdministradores();
        crear(Map.of("name", "Dueño", "username", "recepcion", "pin", "4321"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.username").value("Ese usuario ya existe."));
    }
}

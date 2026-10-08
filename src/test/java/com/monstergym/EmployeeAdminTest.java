package com.monstergym;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmployeeAdminTest extends ApiTestBase {

    private Map<String, Object> empleado(String name, String username, String role, Object pinOrActive) {
        Map<String, Object> m = new HashMap<>(Map.of("name", name, "username", username, "role", role,
                "shiftLabel", "Vespertino"));
        if (pinOrActive instanceof Boolean b) m.put("active", b); else m.put("pin", pinOrActive);
        return m;
    }

    private ResultActions crear(MockHttpSession s, Map<String, Object> data) throws Exception {
        return mvc.perform(post("/api/employees").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(data)));
    }

    private ResultActions editar(MockHttpSession s, long id, Map<String, Object> data) throws Exception {
        return mvc.perform(put("/api/employees/" + id).session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(data)));
    }

    @Test
    void soloAdministrador() throws Exception {
        mvc.perform(get("/api/employees").session(loginAs("recepcion"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/employees").session(loginAs("entrenador"))).andExpect(status().isForbidden());
        crear(loginAs("recepcion"), empleado("X", "xxx", "recepcion", "1234")).andExpect(status().isForbidden());
        mvc.perform(get("/api/employees").session(loginAs("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[3].username").value("inactiva")) // inactivos al final
                .andExpect(jsonPath("$[0].pinHash").doesNotExist());
    }

    @Test
    void alta_yElNuevoEmpleadoPuedeEntrar() throws Exception {
        crear(loginAs("admin"), empleado("Lucía Méndez", " Lucia.Mendez ", "recepcion", "s3cr3t"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("lucia.mendez"))
                .andExpect(jsonPath("$.role").value("recepcion"))
                .andExpect(jsonPath("$.shiftLabel").value("Vespertino"))
                .andExpect(jsonPath("$.active").value(true));
        assertThat(postLogin("lucia.mendez", "s3cr3t").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT pin_hash FROM employee WHERE username = 'lucia.mendez'", String.class))
                .startsWith("$2");
    }

    @Test
    void alta_validaciones() throws Exception {
        MockHttpSession s = loginAs("admin");
        crear(s, empleado("Otro", "recepcion", "recepcion", "1234"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.username").value("Ese usuario ya existe."));
        crear(s, empleado("Otro", "otro", "gerente", "1234"))
                .andExpect(jsonPath("$.error.fields.role").exists());
        crear(s, empleado("Otro", "otro", "recepcion", "12"))
                .andExpect(jsonPath("$.error.fields.pin").exists());
        crear(s, empleado("Otro", "o t", "recepcion", "1234"))
                .andExpect(jsonPath("$.error.fields.username").exists());
        crear(s, empleado(" ", "otro", "recepcion", "1234"))
                .andExpect(jsonPath("$.error.fields.name").exists());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee", Integer.class)).isEqualTo(4);
    }

    @Test
    void desactivar_noPuedeEntrarYSuSesionSeCierra() throws Exception {
        MockHttpSession sesionRecepcion = loginAs("recepcion");
        editar(loginAs("admin"), 2, empleado("Rocío Recepción", "recepcion", "recepcion", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(postLogin("recepcion", PIN).getResponse().getStatus()).isEqualTo(401);
        mvc.perform(get("/api/members").session(sesionRecepcion)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").session(sesionRecepcion)).andExpect(status().isUnauthorized());
    }

    @Test
    void editar_rolYUsuario() throws Exception {
        MockHttpSession s = loginAs("admin");
        editar(s, 3, empleado("Ernesto E.", "ernesto", "recepcion", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ernesto E."))
                .andExpect(jsonPath("$.role").value("recepcion"));
        editar(s, 3, empleado("Ernesto E.", "admin", "recepcion", true))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.username").value("Ese usuario ya existe."));
        editar(s, 99, empleado("X", "xyz", "recepcion", true)).andExpect(status().isNotFound());
    }

    @Test
    void cambioDePin_yQuitaElBloqueo() throws Exception {
        jdbc.update("UPDATE employee SET failed_attempts = 5, locked_until = '2999-01-01T00:00:00' WHERE id = 2");
        MockHttpSession admin = loginAs("admin");
        mvc.perform(get("/api/employees").session(admin)).andExpect(jsonPath("$[?(@.id == 2)].locked").value(true));
        mvc.perform(put("/api/employees/2/pin").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("pin", "9876"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
        assertThat(postLogin("recepcion", PIN).getResponse().getStatus()).isEqualTo(401);
        assertThat(postLogin("recepcion", "9876").getResponse().getStatus()).isEqualTo(200);
        mvc.perform(put("/api/employees/2/pin").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("pin", "1")))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void administrador_noPuedeDesactivarseNiQuitarseElRol() throws Exception {
        MockHttpSession s = loginAs("admin");
        editar(s, 1, empleado("Admin Prueba", "admin", "administrador", false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OPERACION_NO_PERMITIDA"));
        editar(s, 1, empleado("Admin Prueba", "admin", "recepcion", true))
                .andExpect(status().isConflict());
        // Sí puede cambiar su propio nombre
        editar(s, 1, empleado("Admin Nuevo Nombre", "admin", "administrador", true)).andExpect(status().isOk());
    }

    @Test
    void otroAdministrador_siPuedeSerDegradado() throws Exception {
        MockHttpSession s = loginAs("admin");
        crear(s, empleado("Segunda Admin", "admin2", "administrador", "1234")).andExpect(status().isCreated());
        long id = jdbc.queryForObject("SELECT id FROM employee WHERE username = 'admin2'", Long.class);
        editar(s, id, empleado("Segunda Admin", "admin2", "recepcion", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("recepcion"));
    }
}

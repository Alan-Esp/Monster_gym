package com.monstergym;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contexto completo con una base SQLite temporal. Cada prueba empieza con los mismos empleados. */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestBase {

    protected static final String PIN = "1234";
    // Se calcula una sola vez: BCrypt es lento a propósito
    private static final String PIN_HASH = new BCryptPasswordEncoder().encode(PIN);
    private static final Path DB = tempDb();

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;

    @DynamicPropertySource
    static void dbPath(DynamicPropertyRegistry registry) {
        registry.add("app.db.path", DB::toString);
    }

    @BeforeEach
    void resetData() {
        for (String table : new String[]{"print_job", "stock_movement", "sale_item", "sale", "visit",
                "membership", "payment", "shift", "member", "product", "plan", "employee"}) {
            jdbc.update("DELETE FROM " + table);
        }
        // Los mismos planes que crea V3 (las pruebas pueden cambiar precios)
        jdbc.update("INSERT INTO plan (id, name, days, price_cents) VALUES (1, 'Semanal', 7, 12000), "
                + "(2, 'Quincenal', 15, 18000), (3, 'Mensual', 30, 30000), (4, 'Anual', 365, 300000)");
        insertEmployee(1, "Admin Prueba", "admin", "administrador", true);
        insertEmployee(2, "Rocío Recepción", "recepcion", "recepcion", true);
        insertEmployee(3, "Ernesto Entrenador", "entrenador", "entrenador", true);
        insertEmployee(4, "Inés Inactiva", "inactiva", "recepcion", false);
    }

    private void insertEmployee(long id, String name, String username, String role, boolean active) {
        jdbc.update("INSERT INTO employee (id, name, username, role, pin_hash, active) VALUES (?, ?, ?, ?, ?, ?)",
                id, name, username, role, PIN_HASH, active ? 1 : 0);
    }

    protected MvcResult postLogin(String username, String pin) throws Exception {
        return mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "pin", pin))))
                .andReturn();
    }

    /** Inicia sesión y devuelve la sesión HTTP para usarla en las siguientes peticiones. */
    protected MockHttpSession loginAs(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "pin", PIN))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    protected String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    private static Path tempDb() {
        try {
            Path dir = Files.createDirectories(Path.of("target"));
            Path db = Files.createTempFile(dir, "api-test-", ".db");
            Files.delete(db);
            return db;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

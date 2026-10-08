package com.monstergym;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanTest extends ApiTestBase {

    @Test
    void listaSoloPlanesActivosOrdenadosPorDuracion() throws Exception {
        jdbc.update("INSERT INTO plan (name, days, price_cents, active) VALUES ('Trimestral', 90, 120000, 0)");
        mvc.perform(get("/api/plans").session(loginAs("entrenador")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Semanal", "Quincenal", "Mensual", "Anual")))
                .andExpect(jsonPath("$[0].priceCents").value(12000))
                .andExpect(jsonPath("$[0].days").value(7));
    }

    @Test
    void soloAdministradorCambiaPrecios() throws Exception {
        String precio = body(Map.of("priceCents", 32000));
        mvc.perform(put("/api/plans/3").session(loginAs("recepcion")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(precio)).andExpect(status().isForbidden());
        mvc.perform(put("/api/plans/3").session(loginAs("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(precio))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceCents").value(32000));
    }

    @Test
    void precioInvalidoOPlanInexistente() throws Exception {
        var admin = loginAs("admin");
        mvc.perform(put("/api/plans/3").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("priceCents", -1))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.priceCents").exists());
        mvc.perform(put("/api/plans/99").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("priceCents", 100)))).andExpect(status().isNotFound());
    }
}

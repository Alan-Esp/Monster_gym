package com.monstergym;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemberTest extends ApiTestBase {

    private final LocalDate hoy = today();

    private ResultActions create(MockHttpSession s, Map<String, Object> data) throws Exception {
        return mvc.perform(post("/api/members").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(data)));
    }

    private Map<String, Object> socio(String name, String phone) {
        Map<String, Object> m = new HashMap<>();
        m.put("fullName", name);
        m.put("phone", phone);
        return m;
    }

    private void abrirTurno(MockHttpSession s) throws Exception {
        mvc.perform(post("/api/shifts/open").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("openingCashCents", 0))))
                .andExpect(status().isCreated());
    }

    private void socios() {
        insertMember(1, "0412", "Daniela Ortega", "5512348890", 21);    // activa
        insertMember(2, "0087", "Luis Fernando Cruz", "5590871123", 2); // por vencer
        insertMember(3, "0305", "Óscar Galindo", "5511883920", -2);     // vencida
        insertMember(4, "0276", "Ricardo Peña", "5522096618", 15);      // activa
        insertMember(5, "0500", "Hugo Navarro", null, null);            // vencida (sin membresía)
    }

    private ResultActions buscar(String query) throws Exception {
        return mvc.perform(get("/api/members").param("q", query).session(loginAs("entrenador")));
    }

    // ── Alta ───────────────────────────────────────────────────────────────────

    @Test
    void alta_numeroConsecutivoDe4Digitos() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        create(s, socio("Primer Socio", null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.member.number").value("0001"))
                .andExpect(jsonPath("$.member.status").value("vencida"))
                .andExpect(jsonPath("$.payment").doesNotExist());
        create(s, socio("Segundo Socio", null)).andExpect(jsonPath("$.member.number").value("0002"));

        insertMember(50, "0500", "Hugo Navarro", null, null);
        create(s, socio("Tercer Socio", null)).andExpect(jsonPath("$.member.number").value("0501"));
    }

    @Test
    void alta_telefonoSeGuardaSoloConDigitos() throws Exception {
        create(loginAs("recepcion"), socio("  Ana   María  López ", "55 1234-8890"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.member.fullName").value("Ana María López"))
                .andExpect(jsonPath("$.member.phone").value("5512348890"));
    }

    @Test
    void alta_validaciones() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        create(s, socio("", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDACION"))
                .andExpect(jsonPath("$.error.fields.fullName").value("El nombre es obligatorio."));
        create(s, socio("Socio", "55 1234 889"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.phone").value("El teléfono debe tener 10 dígitos."));
        create(s, socio("Socio", "55-ABC-1234"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.phone").exists());
        create(s, socio("x".repeat(101), null))
                .andExpect(jsonPath("$.error.fields.fullName").exists());

        Map<String, Object> conPlanSinMetodo = socio("Socio", null);
        conPlanSinMetodo.put("planId", 3);
        create(s, conPlanSinMetodo)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.method").exists());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member", Integer.class)).isZero();
    }

    @Test
    void alta_conPlan_cobraEnElMismoPaso() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        abrirTurno(s);
        Map<String, Object> data = socio("Nuevo Con Plan", "5511112222");
        data.put("planId", 1);
        data.put("method", "efectivo");
        create(s, data)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.member.status").value("activa")) // 7 días: quedan 7 > warn_days (3)
                .andExpect(jsonPath("$.member.plan").value("Semanal"))
                .andExpect(jsonPath("$.member.validUntil").value(hoy.plusDays(7).toString()))
                .andExpect(jsonPath("$.payment.amountCents").value(12000))
                .andExpect(jsonPath("$.payment.member.number").value("0001"));
    }

    @Test
    void alta_conPlanSinTurno_noCreaElSocio() throws Exception {
        Map<String, Object> data = socio("Sin Turno", null);
        data.put("planId", 3);
        data.put("method", "efectivo");
        create(loginAs("recepcion"), data)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SIN_TURNO"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member", Integer.class)).isZero();
    }

    @Test
    void entrenador_consultaPeroNoCreaNiEdita() throws Exception {
        socios();
        MockHttpSession s = loginAs("entrenador");
        mvc.perform(get("/api/members").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/members/1").session(s)).andExpect(status().isOk());
        create(s, socio("No Permitido", null)).andExpect(status().isForbidden());
        mvc.perform(put("/api/members/1").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(socio("Cambio", null)))).andExpect(status().isForbidden());
    }

    // ── Búsqueda y filtros ─────────────────────────────────────────────────────

    @Test
    void busqueda_porNombreSinAcentosNiEspaciosNiMayusculas() throws Exception {
        socios();
        buscar("oscar").andExpect(jsonPath("$.items[*].fullName", contains("Óscar Galindo")));
        buscar("PEÑA").andExpect(jsonPath("$.items[*].fullName", contains("Ricardo Peña")));
        buscar("pena").andExpect(jsonPath("$.items[*].fullName", contains("Ricardo Peña")));
        buscar("luis fernando").andExpect(jsonPath("$.items[*].fullName", contains("Luis Fernando Cruz")));
        buscar("luisfer").andExpect(jsonPath("$.items[*].fullName", contains("Luis Fernando Cruz")));
        buscar("zzz").andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void busqueda_porNumeroDeSocio() throws Exception {
        socios();
        buscar("0412").andExpect(jsonPath("$.items[*].number", contains("0412")));
        buscar("#0087").andExpect(jsonPath("$.items[*].number", contains("0087")));
    }

    @Test
    void busqueda_porTelefonoConOSinEspacios() throws Exception {
        socios();
        buscar("55 9087 1123").andExpect(jsonPath("$.items[*].fullName", contains("Luis Fernando Cruz")));
        buscar("1188-39").andExpect(jsonPath("$.items[*].fullName", contains("Óscar Galindo")));
    }

    @Test
    void filtrosPorEstadoYConteos() throws Exception {
        socios();
        MockHttpSession s = loginAs("recepcion");
        mvc.perform(get("/api/members").session(s))
                .andExpect(jsonPath("$.registered").value(5))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.counts.todos").value(5))
                .andExpect(jsonPath("$.counts.activa").value(2))
                .andExpect(jsonPath("$.counts.por_vencer").value(1))
                .andExpect(jsonPath("$.counts.vencida").value(2))
                // Orden alfabético ignorando acentos
                .andExpect(jsonPath("$.items[*].fullName", contains("Daniela Ortega", "Hugo Navarro",
                        "Luis Fernando Cruz", "Óscar Galindo", "Ricardo Peña")));

        mvc.perform(get("/api/members").param("status", "vencida").session(s))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].fullName", containsInAnyOrder("Óscar Galindo", "Hugo Navarro")));

        mvc.perform(get("/api/members").param("status", "activa").param("q", "ricardo").session(s))
                .andExpect(jsonPath("$.items[*].fullName", contains("Ricardo Peña")))
                .andExpect(jsonPath("$.counts.todos").value(1))
                .andExpect(jsonPath("$.registered").value(5));

        mvc.perform(get("/api/members").param("status", "otra").session(s))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void itemDeLista_traePlanVigenciaYEstado() throws Exception {
        socios();
        buscar("daniela")
                .andExpect(jsonPath("$.items[0].id").value(1))
                .andExpect(jsonPath("$.items[0].phone").value("5512348890"))
                .andExpect(jsonPath("$.items[0].plan").value("Mensual"))
                .andExpect(jsonPath("$.items[0].validUntil").value(hoy.plusDays(21).toString()))
                .andExpect(jsonPath("$.items[0].status").value("activa"));
    }

    // ── Detalle y edición ──────────────────────────────────────────────────────

    @Test
    void detalle_eEdicion() throws Exception {
        socios();
        MockHttpSession s = loginAs("recepcion");
        mvc.perform(get("/api/members/1").session(s))
                .andExpect(jsonPath("$.number").value("0412"))
                .andExpect(jsonPath("$.status").value("activa"))
                .andExpect(jsonPath("$.daysLeft").value(21))
                .andExpect(jsonPath("$.payments.length()").value(1))
                .andExpect(jsonPath("$.visits.length()").value(0));

        Map<String, Object> cambio = socio("Daniela Ortega Ruiz", "5500001111");
        cambio.put("notes", "Lesión de rodilla");
        mvc.perform(put("/api/members/1").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(cambio)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Daniela Ortega Ruiz"))
                .andExpect(jsonPath("$.notes").value("Lesión de rodilla"));
        buscar("ortegaruiz").andExpect(jsonPath("$.items[*].number", contains("0412")));

        mvc.perform(get("/api/members/999").session(s)).andExpect(status().isNotFound());
    }

    // ── Baja ───────────────────────────────────────────────────────────────────

    @Test
    void baja_soloElAdministrador() throws Exception {
        socios();
        for (String user : new String[]{"recepcion", "entrenador"}) {
            mvc.perform(delete("/api/members/1").session(loginAs(user)).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT active FROM member WHERE id = 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void baja_ocultaAlSocioPeroConservaSuHistorial() throws Exception {
        socios();
        jdbc.update("UPDATE member SET fingerprint_template = X'01', fingerprint_consent_at = '2026-01-01' WHERE id = 1");
        jdbc.update("INSERT INTO visit (member_id, employee_id, checked_in_at, method, status_at_entry) "
                + "VALUES (1, 2, '2026-01-02T10:00:00', 'manual', 'activa')");
        MockHttpSession admin = loginAs("admin");

        mvc.perform(delete("/api/members/1").session(admin).with(csrf())).andExpect(status().isNoContent());

        mvc.perform(get("/api/members/1").session(admin)).andExpect(status().isNotFound());
        buscar("daniela").andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/members").session(admin))
                .andExpect(jsonPath("$.counts.todos").value(4))
                .andExpect(jsonPath("$.registered").value(4));
        assertThat(jdbc.queryForMap("SELECT active, fingerprint_template, fingerprint_consent_at FROM member WHERE id = 1"))
                .containsEntry("active", 0)
                .containsEntry("fingerprint_template", null)
                .containsEntry("fingerprint_consent_at", null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE member_id = 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM visit WHERE member_id = 1", Integer.class)).isEqualTo(1);
        // Ya no se puede cobrar ni editar
        mvc.perform(put("/api/members/1").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(socio("Otro", null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void baja_deSocioInexistenteOYaDadoDeBaja() throws Exception {
        socios();
        MockHttpSession admin = loginAs("admin");
        mvc.perform(delete("/api/members/999").session(admin).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/members/1").session(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/members/1").session(admin).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ENCONTRADO"));
    }
}

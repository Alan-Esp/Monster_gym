package com.monstergym;

import com.monstergym.fingerprint.FakeReader;
import com.monstergym.fingerprint.FingerprintEvents;
import com.monstergym.fingerprint.FingerprintReader;
import com.monstergym.fingerprint.FingerprintService;
import jakarta.servlet.http.HttpSessionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Huella con el lector simulado: enrolamiento, identificación 1 a N, eventos SSE y desconexión. */
class FingerprintTest extends ApiTestBase {

    @Autowired private FingerprintReader reader;
    @Autowired private FingerprintService service;
    @Autowired private FingerprintEvents events;

    private FakeReader fake;
    private final List<MockHttpSession> sessions = new ArrayList<>();

    @BeforeEach
    void resetReader() {
        fake = (FakeReader) reader;
        fake.cancelEnroll();
        fake.setConnected(true);
        if (!service.enabled()) service.setEnabled(true);
        jdbc.update("DELETE FROM setting WHERE key = 'fingerprint_enabled'");
        service.reload();
    }

    @AfterEach
    void closeSubscriptions() {
        sessions.forEach(s -> events.closeSession(s.getId()));
    }

    // ── Ayudas ─────────────────────────────────────────────────────────────────

    /** Abre /api/fingerprint/events; el contenido de la respuesta va acumulando los eventos. */
    private MvcResult subscribe(MockHttpSession s) throws Exception {
        sessions.add(s);
        return mvc.perform(get("/api/fingerprint/events").session(s).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private static String sse(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("No se cumplió la condición a tiempo");
            Thread.sleep(10);
        }
    }

    private static void waitForEvent(MvcResult r, String text) throws InterruptedException {
        waitFor(() -> {
            try {
                return sse(r).contains(text);
            } catch (Exception e) {
                return false;
            }
        });
    }

    private String startEnroll(MockHttpSession s, String path) throws Exception {
        MvcResult r = mvc.perform(post(path).session(s).with(csrf()))
                .andExpect(status().isAccepted())
                .andReturn();
        String id = json.readTree(r.getResponse().getContentAsString()).get("enrollmentId").asText();
        waitFor(fake::enrolling);
        return id;
    }

    private void touch(String finger, int times) throws Exception {
        for (int i = 0; i < times; i++) fake.touch(finger);
        service.awaitIdle();
    }

    private void enrollExisting(MockHttpSession s, MvcResult stream, long memberId, String finger) throws Exception {
        String id = startEnroll(s, "/api/fingerprint/enroll/" + memberId);
        touch(finger, 4);
        waitForEvent(stream, "\"enrollmentId\":\"" + id + "\",\"status\":\"listo\"");
    }

    private int visits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM visit", Integer.class);
    }

    // ── Estado y permisos ─────────────────────────────────────────────────────

    @Test
    void estado_loVenTodosLosRoles() throws Exception {
        mvc.perform(get("/api/fingerprint/status").session(loginAs("entrenador")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.mode").value("fake"));
    }

    @Test
    void entrenador_noRecibeEventosNiEnrola() throws Exception {
        MockHttpSession s = loginAs("entrenador");
        mvc.perform(get("/api/fingerprint/events").session(s)).andExpect(status().isForbidden());
        mvc.perform(post("/api/fingerprint/enroll").session(s).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void suscripcion_recibeElEstadoAlConectarse() throws Exception {
        MvcResult stream = subscribe(loginAs("recepcion"));
        assertThat(sse(stream)).contains("event:estado").contains("\"connected\":true");
    }

    // ── Enrolamiento e identificación ─────────────────────────────────────────

    @Test
    void socioEnrolado_entraConHuellaANombreDeRecepcion() throws Exception {
        insertMember(1, "0412", "Daniela Ortega", null, 21);
        MockHttpSession s = loginAs("recepcion");
        MvcResult stream = subscribe(s);
        enrollExisting(s, stream, 1, "daniela");

        assertThat(sse(stream)).contains("\"status\":\"muestra\",\"remaining\":3");
        assertThat(jdbc.queryForObject("SELECT fingerprint_template IS NOT NULL FROM member WHERE id = 1", Boolean.class))
                .isTrue();
        mvc.perform(get("/api/members/1").session(s)).andExpect(jsonPath("$.hasFingerprint").value(true));

        touch("daniela", 1);
        assertThat(jdbc.queryForMap("SELECT method, status_at_entry, employee_id FROM visit"))
                .containsEntry("method", "huella")
                .containsEntry("status_at_entry", "activa")
                .containsEntry("employee_id", 2);
        assertThat(sse(stream)).contains("event:identificado").contains("Daniela Ortega").contains("\"duplicate\":false");
        mvc.perform(get("/api/visits").session(s)).andExpect(jsonPath("$.fingerprintPct").value(100));
    }

    @Test
    void plantillasGuardadas_seCarganAlArrancar() throws Exception {
        insertMember(1, "0001", "Ana Uno", null, 10);
        insertMember(2, "0002", "Beto Dos", null, 10);
        jdbc.update("UPDATE member SET fingerprint_template = ? WHERE id = 1", "FAKE1:ana".getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE member SET fingerprint_template = ? WHERE id = 2", "FAKE1:beto".getBytes(StandardCharsets.UTF_8));
        service.reload();
        MvcResult stream = subscribe(loginAs("recepcion"));

        touch("beto", 1);
        assertThat(jdbc.queryForObject("SELECT member_id FROM visit", Long.class)).isEqualTo(2L);
        assertThat(sse(stream)).contains("Beto Dos");
        assertThat(service.status().enrolled()).isEqualTo(2);
    }

    @Test
    void huellaDesconocida_noReconocidaYSinVisita() throws Exception {
        insertMember(1, "0001", "Ana Uno", null, 10);
        MockHttpSession s = loginAs("recepcion");
        MvcResult stream = subscribe(s);
        enrollExisting(s, stream, 1, "ana");

        touch("desconocido", 1);
        assertThat(visits()).isZero();
        assertThat(sse(stream)).contains("event:no_reconocido").contains("Huella no reconocida");
    }

    @Test
    void socioVencido_entraMarcadoComoVencido() throws Exception {
        insertMember(1, "0398", "Jorge Medina", null, -2);
        MockHttpSession s = loginAs("recepcion");
        MvcResult stream = subscribe(s);
        enrollExisting(s, stream, 1, "jorge");

        touch("jorge", 1);
        assertThat(jdbc.queryForObject("SELECT status_at_entry FROM visit", String.class)).isEqualTo("vencida");
        assertThat(sse(stream)).contains("\"statusAtEntry\":\"vencida\"");
    }

    @Test
    void dobleHuellaEnMenosDe5Minutos_noDuplicaLaVisita() throws Exception {
        insertMember(1, "0001", "Ana Uno", null, 10);
        MockHttpSession s = loginAs("recepcion");
        MvcResult stream = subscribe(s);
        enrollExisting(s, stream, 1, "ana");

        touch("ana", 2);
        assertThat(visits()).isEqualTo(1);
        assertThat(sse(stream)).contains("\"duplicate\":true").contains("Ana Uno ya registró entrada a las");
    }

    @Test
    void sinNadieConSesion_laHuellaSeIgnora() throws Exception {
        insertMember(1, "0001", "Ana Uno", null, 10);
        jdbc.update("UPDATE member SET fingerprint_template = ? WHERE id = 1", "FAKE1:ana".getBytes(StandardCharsets.UTF_8));
        service.reload();

        touch("ana", 1);
        assertThat(visits()).isZero();
    }

    @Test
    void alCerrarSesion_seDejaDeRegistrarASuNombre() throws Exception {
        insertMember(1, "0001", "Ana Uno", null, 10);
        jdbc.update("UPDATE member SET fingerprint_template = ? WHERE id = 1", "FAKE1:ana".getBytes(StandardCharsets.UTF_8));
        service.reload();
        MockHttpSession s = loginAs("recepcion");
        subscribe(s);
        mvc.perform(post("/api/auth/logout").session(s).with(csrf())).andExpect(status().isNoContent());
        assertThat(s.isInvalid()).isTrue();
        // Tomcat avisa a los HttpSessionListener al invalidar la sesión; MockMvc no, así que se simula
        events.sessionDestroyed(new HttpSessionEvent(s));

        touch("ana", 1);
        assertThat(visits()).isZero();
        assertThat(events.attendingEmployee()).isEmpty();
    }

    // ── Nuevo socio (captura pendiente) ───────────────────────────────────────

    @Test
    void nuevoSocio_guardaLaHuellaCapturadaAntesDelAlta() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        MvcResult stream = subscribe(s);
        String id = startEnroll(s, "/api/fingerprint/enroll");
        // Por el endpoint de desarrollo, como lo haría el navegador
        for (int i = 0; i < 4; i++) {
            mvc.perform(post("/api/fingerprint/fake/touch").session(s).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("finger", "nuevo"))))
                    .andExpect(status().isNoContent());
        }
        waitForEvent(stream, "\"enrollmentId\":\"" + id + "\",\"status\":\"listo\"");

        long memberId = json.readTree(mvc.perform(post("/api/members").session(s).with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(Map.of("fullName", "Socio Nuevo", "fingerprintEnrollmentId", id))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.member.hasFingerprint").value(true))
                        .andReturn().getResponse().getContentAsString())
                .get("member").get("id").asLong();

        touch("nuevo", 1);
        assertThat(jdbc.queryForObject("SELECT member_id FROM visit", Long.class)).isEqualTo(memberId);
        assertThat(jdbc.queryForObject("SELECT fingerprint_consent_at FROM member WHERE id = ?", String.class, memberId))
                .isNull();
    }

    @Test
    void nuevoSocio_conCapturaInexistente_noSeCrea() throws Exception {
        mvc.perform(post("/api/members").session(loginAs("recepcion")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("fullName", "Socio Nuevo", "fingerprintEnrollmentId", "no-existe"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDACION"))
                .andExpect(jsonPath("$.error.fields.fingerprintEnrollmentId").exists());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member", Integer.class)).isZero();
    }

    @Test
    void nuevoSocio_siFallaElCobro_laHuellaSigueDisponible() throws Exception {
        MockHttpSession s = loginAs("recepcion");  // sin turno abierto: el cobro falla
        MvcResult stream = subscribe(s);
        String id = startEnroll(s, "/api/fingerprint/enroll");
        touch("nuevo", 4);
        waitForEvent(stream, "\"status\":\"listo\"");

        Map<String, Object> alta = Map.of("fullName", "Socio Nuevo", "planId", 3, "method", "efectivo",
                "fingerprintEnrollmentId", id);
        mvc.perform(post("/api/members").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(alta)))
                .andExpect(status().isConflict());
        touch("nuevo", 1);
        assertThat(visits()).isZero();  // la huella no quedó en caché

        mvc.perform(post("/api/shifts/open").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("openingCashCents", 0))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/members").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(alta)))
                .andExpect(status().isCreated());
    }

    // ── Errores del enrolamiento ──────────────────────────────────────────────

    @Test
    void muestraDeMalaCalidad_seRepiteYMuestrasDistintasFallan() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        MvcResult stream = subscribe(s);
        startEnroll(s, "/api/fingerprint/enroll");
        touch("", 1);
        touch("a", 1);
        touch("b", 1);
        waitForEvent(stream, "\"status\":\"error\"");
        assertThat(sse(stream)).contains("\"status\":\"calidad\"").contains("no son del mismo dedo");
    }

    @Test
    void soloUnaCapturaALaVez_yLaSePuedeCancelar() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        MvcResult stream = subscribe(s);
        String id = startEnroll(s, "/api/fingerprint/enroll");
        mvc.perform(post("/api/fingerprint/enroll").session(s).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CAPTURA_EN_CURSO"));

        mvc.perform(post("/api/fingerprint/enroll/" + id + "/cancel").session(s).with(csrf()))
                .andExpect(status().isNoContent());
        waitForEvent(stream, "Captura de huella cancelada.");
        waitFor(() -> !fake.enrolling());
        startEnroll(s, "/api/fingerprint/enroll");  // ya se puede volver a capturar
    }

    @Test
    void enrolarSocioInexistente_404() throws Exception {
        mvc.perform(post("/api/fingerprint/enroll/999").session(loginAs("recepcion")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    // ── Desconexión y configuración ───────────────────────────────────────────

    @Test
    void lectorDesconectado_avisaYElAccesoManualSigue() throws Exception {
        insertMember(1, "0001", "Ana Uno", null, 10);
        MockHttpSession s = loginAs("recepcion");
        MvcResult stream = subscribe(s);
        startEnroll(s, "/api/fingerprint/enroll");

        fake.setConnected(false);
        waitForEvent(stream, "Se desconectó el lector de huella.");
        assertThat(sse(stream)).contains("\"connected\":false");
        mvc.perform(get("/api/fingerprint/status").session(s)).andExpect(jsonPath("$.connected").value(false));
        mvc.perform(post("/api/fingerprint/enroll").session(s).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LECTOR_DESCONECTADO"));

        mvc.perform(post("/api/visits").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("memberId", 1, "method", "manual"))))
                .andExpect(status().isCreated());
    }

    @Test
    void lectorDesactivadoEnConfiguracion_noCapturaNiEnrola() throws Exception {
        insertMember(1, "0001", "Ana Uno", null, 10);
        jdbc.update("UPDATE member SET fingerprint_template = ? WHERE id = 1", "FAKE1:ana".getBytes(StandardCharsets.UTF_8));
        service.reload();
        MockHttpSession admin = loginAs("admin");
        subscribe(admin);
        mvc.perform(put("/api/settings").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("fingerprintEnabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fingerprintEnabled").value(false));

        touch("ana", 1);
        assertThat(visits()).isZero();
        mvc.perform(get("/api/fingerprint/status").session(admin))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.connected").value(false));
        mvc.perform(post("/api/fingerprint/enroll").session(admin).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("HUELLA_DESACTIVADA"));

        mvc.perform(put("/api/settings").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("fingerprintEnabled", true))))
                .andExpect(status().isOk());
        touch("ana", 1);
        assertThat(visits()).isEqualTo(1);
    }
}

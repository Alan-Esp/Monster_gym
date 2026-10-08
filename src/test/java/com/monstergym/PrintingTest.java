package com.monstergym;

import com.fasterxml.jackson.databind.JsonNode;
import com.monstergym.printing.FakePrinter;
import com.monstergym.printing.TicketPrinter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tickets: impresión automática asíncrona, reintentos, reimpresión COPIA, prueba, estado y permisos. */
class PrintingTest extends ApiTestBase {

    @Autowired TicketPrinter printer;

    private FakePrinter fake() {
        return (FakePrinter) printer;
    }

    @BeforeEach
    void member() {
        fake().failNext(0);
        fake().setDelayMs(0);
        insertMember(1, "0001", "Daniela Ortega", null, null);
    }

    @AfterEach
    void drainQueue() throws Exception {
        fake().failNext(0);
        fake().setDelayMs(0);
        // Que ningún trabajo de esta prueba siga vivo en la siguiente
        for (int i = 0; i < 200 && unfinished() > 0; i++) Thread.sleep(25);
    }

    private int unfinished() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM print_job WHERE status IN ('pendiente','enviando')", Integer.class);
    }

    private JsonNode charge(MockHttpSession s) throws Exception {
        String res = mvc.perform(post("/api/payments").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("memberId", 1, "planId", 3, "method", "efectivo"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(res);
    }

    /** Espera a que el trabajo termine y devuelve su fila. */
    private Map<String, Object> awaitJob(long jobId) throws Exception {
        for (int i = 0; i < 400; i++) {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM print_job WHERE id = ?", jobId);
            if (!"pendiente".equals(row.get("status")) && !"enviando".equals(row.get("status"))) return row;
            Thread.sleep(25);
        }
        throw new AssertionError("El trabajo " + jobId + " no terminó");
    }

    private String ticketText(long jobId, String kind) throws Exception {
        return Files.readString(fake().dir().resolve(String.format("%06d-%s.txt", jobId, kind)), StandardCharsets.UTF_8);
    }

    private void setting(String key, String value) {
        jdbc.update("UPDATE setting SET value = ? WHERE key = ?", value, key);
    }

    @Test
    void cobro_imprimeSoloYNoEspera() throws Exception {
        JsonNode r = charge(loginWithShift("recepcion", 0));
        assertThat(r.get("folio").asText()).isNotBlank();       // los campos del cobro siguen en la raíz
        assertThat(r.at("/print/status").asText()).isEqualTo("pendiente");
        long jobId = r.at("/print/jobId").asLong();

        Map<String, Object> job = awaitJob(jobId);
        assertThat(job).containsEntry("status", "entregado").containsEntry("kind", "cobro")
                .containsEntry("transport", "archivo").containsEntry("is_copy", 0).containsEntry("attempts", 1);
        assertThat(((Number) job.get("ref_id")).longValue()).isEqualTo(r.get("id").asLong());
        assertThat(job.get("finished_at")).isNotNull();
        assertThat(ticketText(jobId, "cobro")).contains("COBRO DE MEMBRESÍA", "Daniela Ortega", "$300.00")
                .doesNotContain("COPIA");
        assertThat(Files.exists(fake().dir().resolve(String.format("%06d-cobro.bin", jobId)))).isTrue();
    }

    @Test
    void impresoraApagada_elCobroSeGuardaYElTrabajoQuedaFallido() throws Exception {
        setting("print_retries", "1");
        fake().failNext(10);
        JsonNode r = charge(loginWithShift("recepcion", 0));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE id = ?", Integer.class, r.get("id").asLong()))
                .isEqualTo(1);
        Map<String, Object> job = awaitJob(r.at("/print/jobId").asLong());
        assertThat(job).containsEntry("status", "fallido").containsEntry("attempts", 2);
        assertThat((String) job.get("error")).contains("apagada");

        mvc.perform(get("/api/print-jobs/" + job.get("id")).session(loginAs("recepcion")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("fallido"))
                .andExpect(jsonPath("$.error").isString());
    }

    @Test
    void falloPasajero_seReintentaYSeEntrega() throws Exception {
        fake().failNext(2);
        JsonNode r = charge(loginWithShift("recepcion", 0));
        Map<String, Object> job = awaitJob(r.at("/print/jobId").asLong());
        assertThat(job).containsEntry("status", "entregado").containsEntry("attempts", 3);
        assertThat(job.get("error")).isNull();
    }

    @Test
    void impresoraQueNoResponde_seCortaPorTimeout() throws Exception {
        setting("print_timeout_seconds", "1");
        setting("print_retries", "0");
        fake().setDelayMs(1500);
        JsonNode r = charge(loginWithShift("recepcion", 0));
        Map<String, Object> job = awaitJob(r.at("/print/jobId").asLong());
        assertThat(job).containsEntry("status", "fallido");
        assertThat((String) job.get("error")).contains("no respondió en 1 s");
    }

    @Test
    void variosTickets_seImprimenTodosEnOrden() throws Exception {
        MockHttpSession s = loginWithShift("recepcion", 0);
        long a = charge(s).at("/print/jobId").asLong();
        long b = charge(s).at("/print/jobId").asLong();
        long c = charge(s).at("/print/jobId").asLong();
        String fa = (String) awaitJob(a).get("finished_at"), fb = (String) awaitJob(b).get("finished_at"),
                fc = (String) awaitJob(c).get("finished_at");
        assertThat(fa).isLessThanOrEqualTo(fb);
        assertThat(fb).isLessThanOrEqualTo(fc);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM print_job WHERE status = 'entregado'", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void impresionDesactivada_noEncolaYLaReimpresionAvisa() throws Exception {
        setting("print_enabled", "0");
        JsonNode r = charge(loginWithShift("recepcion", 0));
        assertThat(r.at("/print/status").asText()).isEqualTo("desactivada");
        assertThat(r.at("/print/jobId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM print_job", Integer.class)).isZero();

        mvc.perform(post("/api/payments/" + r.get("id").asLong() + "/ticket").session(loginAs("recepcion")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IMPRESION_DESACTIVADA"));
    }

    @Test
    void reimprimirCobro_vaMarcadoCopia_yRespetaRoles() throws Exception {
        JsonNode r = charge(loginWithShift("recepcion", 0));
        awaitJob(r.at("/print/jobId").asLong());
        long paymentId = r.get("id").asLong();

        String res = mvc.perform(post("/api/payments/" + paymentId + "/ticket").session(loginAs("recepcion")).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pendiente"))
                .andReturn().getResponse().getContentAsString();
        long copyJob = json.readTree(res).get("jobId").asLong();
        assertThat(awaitJob(copyJob)).containsEntry("is_copy", 1).containsEntry("status", "entregado");
        assertThat(ticketText(copyJob, "cobro")).contains("*** COPIA ***", "Por: Rocío Recepción");

        mvc.perform(post("/api/payments/" + paymentId + "/ticket").session(loginAs("entrenador")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/payments/999999/ticket").session(loginAs("admin")).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cierre_imprimeSolo_yLaCopiaEsSoloDeAdministrador() throws Exception {
        MockHttpSession s = loginWithShift("recepcion", 50000);
        charge(s);
        String res = mvc.perform(post("/api/shifts/close").session(s).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("countedCashCents", 80000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedCashCents").value(80000))  // el corte sigue en la raíz
                .andExpect(jsonPath("$.print.status").value("pendiente"))
                .andReturn().getResponse().getContentAsString();
        JsonNode closed = json.readTree(res);
        long jobId = closed.at("/print/jobId").asLong();
        assertThat(awaitJob(jobId)).containsEntry("kind", "cierre_turno").containsEntry("status", "entregado");
        assertThat(ticketText(jobId, "cierre_turno")).contains("CIERRE DE TURNO", "1 x Mensual", "Fondo inicial",
                "Diferencia");

        long shiftId = closed.get("id").asLong();
        mvc.perform(post("/api/shifts/" + shiftId + "/ticket").session(loginAs("recepcion")).with(csrf()))
                .andExpect(status().isForbidden());
        String copy = mvc.perform(post("/api/shifts/" + shiftId + "/ticket").session(loginAs("admin")).with(csrf()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        long copyJob = json.readTree(copy).get("jobId").asLong();
        awaitJob(copyJob);
        assertThat(ticketText(copyJob, "cierre_turno")).contains("*** COPIA ***");
    }

    @Test
    void cierreDeTurnoAbierto_noSeReimprime() throws Exception {
        MockHttpSession s = loginWithShift("admin", 0);
        long shiftId = jdbc.queryForObject("SELECT id FROM shift WHERE closed_at IS NULL", Long.class);
        mvc.perform(post("/api/shifts/" + shiftId + "/ticket").session(s).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TURNO_ABIERTO"));
    }

    @Test
    void prueba_soloAdministrador_yTraeAcentos() throws Exception {
        mvc.perform(post("/api/printer/test").session(loginAs("recepcion")).with(csrf()))
                .andExpect(status().isForbidden());
        String res = mvc.perform(post("/api/printer/test").session(loginAs("admin")).with(csrf()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        long jobId = json.readTree(res).get("jobId").asLong();
        assertThat(awaitJob(jobId)).containsEntry("kind", "prueba").containsEntry("status", "entregado");
        assertThat(ticketText(jobId, "prueba")).contains("áéíóúñÑü¿¡$", "ESC t 2", "IBM850");
    }

    @Test
    void estado_paraAdministradorYRecepcion() throws Exception {
        mvc.perform(get("/api/printer/status").session(loginAs("recepcion")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.mode").value("fake"))
                .andExpect(jsonPath("$.transport").value("archivo"))
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.detail").isString());
        mvc.perform(get("/api/printer/status").session(loginAs("entrenador"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/print-jobs/1").session(loginAs("entrenador"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/print-jobs/999999").session(loginAs("admin"))).andExpect(status().isNotFound());

        setting("print_enabled", "0");
        mvc.perform(get("/api/printer/status").session(loginAs("admin")))
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.detail").value("La impresión de tickets está desactivada."));
        setting("print_enabled", "1");
    }

    @Test
    void ultimoTicketFallido_seReflejaEnElEstado() throws Exception {
        setting("print_retries", "0");
        fake().failNext(1);
        awaitJob(charge(loginWithShift("recepcion", 0)).at("/print/jobId").asLong());
        mvc.perform(get("/api/printer/status").session(loginAs("admin")))
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.failedToday").value(1))
                .andExpect(jsonPath("$.lastJob.status").value("fallido"));
    }

    @Test
    void configuracionDeImpresion_seValida() throws Exception {
        MockHttpSession admin = loginAs("admin");
        mvc.perform(put("/api/settings").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("printerPort", "com5", "printerTransport", "windows",
                                "printerCodepage", 16, "printerCharset", "windows-1252"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.printing.port").value("COM5"))
                .andExpect(jsonPath("$.printing.transport").value("windows"))
                .andExpect(jsonPath("$.printing.codepage").value(16))
                .andExpect(jsonPath("$.printing.charset").value("windows-1252"));
        mvc.perform(put("/api/settings").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("printerCharset", "PC-INVENTADO"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.printerCharset").exists());
        mvc.perform(put("/api/settings").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("printerTransport", "usb", "printerPort", "LPT1", "printerCodepage", 300))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.printerTransport").exists())
                .andExpect(jsonPath("$.error.fields.printerPort").exists())
                .andExpect(jsonPath("$.error.fields.printerCodepage").exists());
        // Deja la configuración como la crea V5 para las demás pruebas
        Map.of("printer_port", "COM4", "printer_transport", "serial", "printer_codepage", "2", "printer_charset", "IBM850")
                .forEach(this::setting);
    }

    @Test
    void migracion_creaLaConfiguracionDeLaPt210() {
        Map<String, String> s = new java.util.HashMap<>();
        jdbc.query("SELECT key, value FROM setting", rs -> { s.put(rs.getString(1), rs.getString(2)); });
        assertThat(s).containsKeys("printer_transport", "printer_port", "printer_codepage", "printer_charset",
                "print_timeout_seconds", "print_retries", "printer_status_check");
        assertThat(s.get("printer_name")).isEqualTo("PT-210");
    }
}

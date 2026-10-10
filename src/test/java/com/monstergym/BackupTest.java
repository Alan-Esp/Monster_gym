package com.monstergym;

import com.fasterxml.jackson.databind.JsonNode;
import com.monstergym.backup.BackupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BackupTest extends ApiTestBase {

    @Autowired BackupService backups;

    private Path dir;

    @BeforeEach
    void emptyBackupDir() throws Exception {
        dir = Path.of(backups.list().dir());
        Files.createDirectories(dir);
        for (Path p : listDir()) Files.delete(p);
    }

    private List<Path> listDir() throws Exception {
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            ds.forEach(out::add);
        }
        return out;
    }

    private String todayName() {
        return "monster-gym-" + today() + ".db";
    }

    private byte[] backupBytes(MockHttpSession admin) throws Exception {
        mvc.perform(post("/api/backups").session(admin).with(csrf())).andExpect(status().isCreated());
        return mvc.perform(get("/api/backups/" + todayName()).session(admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
    }

    private ResultActions restore(MockHttpSession s, byte[] content, String confirm) throws Exception {
        var req = multipart("/api/backups/restore")
                .file(new MockMultipartFile("file", "respaldo.db", "application/octet-stream", content));
        if (confirm != null) req.param("confirm", confirm);
        return mvc.perform(req.session(s).with(csrf()));
    }

    // ── Permisos ──────────────────────────────────────────────────────────────

    @Test
    void soloElAdministrador() throws Exception {
        for (String user : new String[]{"recepcion", "entrenador"}) {
            MockHttpSession s = loginAs(user);
            mvc.perform(get("/api/backups").session(s)).andExpect(status().isForbidden());
            mvc.perform(post("/api/backups").session(s).with(csrf())).andExpect(status().isForbidden());
            restore(s, new byte[]{1}, "RESTAURAR").andExpect(status().isForbidden());
        }
        assertThat(listDir()).isEmpty();
    }

    // ── Respaldo ──────────────────────────────────────────────────────────────

    @Test
    void respaldarAhora_creaElArchivoDelDiaYSeDescarga() throws Exception {
        MockHttpSession admin = loginAs("admin");
        mvc.perform(post("/api/backups").session(admin).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(todayName()))
                .andExpect(jsonPath("$.kind").value("diario"));
        mvc.perform(get("/api/backups").session(admin))
                .andExpect(jsonPath("$.keepDays").value(30))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value(todayName()));

        byte[] bytes = mvc.perform(get("/api/backups/" + todayName()).session(admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"" + todayName() + "\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(bytes, 0, 15, StandardCharsets.US_ASCII)).isEqualTo("SQLite format 3");
    }

    @Test
    void respaldarDosVecesElMismoDia_dejaUnSoloArchivo() throws Exception {
        backups.backupToday();
        backups.backupToday();
        assertThat(listDir()).extracting(p -> p.getFileName().toString()).containsExactly(todayName());
    }

    @Test
    void alArrancar_soloRespaldaSiHoyNoHayRespaldo() {
        assertThat(backups.backupTodayIfMissing()).isTrue();
        assertThat(backups.backupTodayIfMissing()).isFalse();
    }

    @Test
    void conservaSoloLosUltimos30Dias() throws Exception {
        for (int i = 1; i <= 35; i++) {
            Files.writeString(dir.resolve("monster-gym-" + today().minusDays(i) + ".db"), "viejo");
        }
        Files.writeString(dir.resolve("otro-archivo.db"), "no se toca");
        backups.backupToday();

        List<String> names = listDir().stream().map(p -> p.getFileName().toString()).toList();
        assertThat(names).hasSize(31)
                .contains(todayName(), "monster-gym-" + today().minusDays(29) + ".db", "otro-archivo.db")
                .doesNotContain("monster-gym-" + today().minusDays(30) + ".db");
    }

    @Test
    void descargar_soloNombresDeRespaldo() throws Exception {
        MockHttpSession admin = loginAs("admin");
        Files.writeString(dir.resolve("otro-archivo.db"), "x");
        mvc.perform(get("/api/backups/otro-archivo.db").session(admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NO_ENCONTRADO"));
        mvc.perform(get("/api/backups/monster-gym-2020-01-01.db").session(admin))
                .andExpect(status().isNotFound());
    }

    // ── Restauración ──────────────────────────────────────────────────────────

    @Test
    void restaurar_regresaLosDatosDelRespaldoYGuardaLaBaseAnterior() throws Exception {
        insertMember(1, "0001", "Antes Del Respaldo", null, 10);
        MockHttpSession admin = loginAs("admin");
        byte[] copia = backupBytes(admin);

        jdbc.update("UPDATE member SET full_name = 'Cambiado Despues' WHERE id = 1");
        insertMember(2, "0002", "Socio Nuevo", null, null);

        restore(admin, copia, "RESTAURAR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredFrom").value("respaldo.db"))
                .andExpect(jsonPath("$.safetyBackup").value(org.hamcrest.Matchers.startsWith("antes-de-restaurar-")));

        assertThat(jdbc.queryForList("SELECT full_name FROM member ORDER BY id", String.class))
                .containsExactly("Antes Del Respaldo");
        assertThat(listDir()).anyMatch(p -> p.getFileName().toString().startsWith("antes-de-restaurar-"));
        // La base anterior quedó guardada: tiene al socio nuevo
        Path safety = listDir().stream()
                .filter(p -> p.getFileName().toString().startsWith("antes-de-restaurar-")).findFirst().orElseThrow();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + safety);
             Statement st = c.createStatement()) {
            var rs = st.executeQuery("SELECT COUNT(*) FROM member");
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
        // La sesión de quien restauró se cierra
        mvc.perform(get("/api/backups").session(admin)).andExpect(status().isUnauthorized());
    }

    @Test
    void restaurar_conTurnoAbierto_noHaceNada() throws Exception {
        insertMember(1, "0001", "Socio Original", null, null);
        MockHttpSession admin = loginAs("admin");
        byte[] copia = backupBytes(admin);
        jdbc.update("UPDATE member SET full_name = 'Sigue Igual' WHERE id = 1");

        MockHttpSession recepcion = loginWithShift("recepcion", 0);
        restore(admin, copia, "RESTAURAR")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TURNO_ABIERTO"));
        assertThat(jdbc.queryForObject("SELECT full_name FROM member WHERE id = 1", String.class))
                .isEqualTo("Sigue Igual");
        assertThat(recepcion).isNotNull();
    }

    @Test
    void restaurar_sinConfirmacion_noHaceNada() throws Exception {
        MockHttpSession admin = loginAs("admin");
        byte[] copia = backupBytes(admin);
        restore(admin, copia, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.confirm").value("Escribe RESTAURAR para confirmar."));
        restore(admin, copia, "si").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void restaurar_archivoQueNoEsBase_seRechaza() throws Exception {
        insertMember(1, "0001", "Socio Original", null, null);
        MockHttpSession admin = loginAs("admin");
        restore(admin, "esto no es una base".getBytes(StandardCharsets.UTF_8), "RESTAURAR")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("RESPALDO_INVALIDO"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member", Integer.class)).isEqualTo(1);
        assertThat(listDir()).isEmpty();
    }

    @Test
    void restaurar_baseSqliteDeOtraApp_seRechaza() throws Exception {
        Path otra = Files.createTempFile(Path.of("target"), "otra-", ".db");
        Files.delete(otra);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + otra);
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE cosas (id INTEGER)");
        }
        restore(loginAs("admin"), Files.readAllBytes(otra), "RESTAURAR")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.message").value("El archivo no es un respaldo de Monster Gym."));
    }

    @Test
    void restaurar_respaldoDeVersionMasNueva_seRechaza() throws Exception {
        MockHttpSession admin = loginAs("admin");
        Path nueva = Files.createTempFile(Path.of("target"), "nueva-", ".db");
        Files.write(nueva, backupBytes(admin));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + nueva);
             Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, "
                    + "installed_by, installed_on, execution_time, success) "
                    + "VALUES (999, '999', 'futuro', 'SQL', 'V999__futuro.sql', 'x', '2030-01-01', 0, 1)");
        }
        JsonNode err = json.readTree(restore(admin, Files.readAllBytes(nueva), "RESTAURAR")
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("error");
        assertThat(err.path("message").asText()).isEqualTo("El respaldo es de una versión más nueva del sistema.");
    }
}

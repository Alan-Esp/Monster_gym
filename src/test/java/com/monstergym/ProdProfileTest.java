package com.monstergym;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Perfil prod en una PC nueva: la carpeta de datos no existe y se crea sola; sin datos de ejemplo. */
@SpringBootTest
@ActiveProfiles("prod")
class ProdProfileTest {

    private static final Path DATA = Path.of("target", "prod-test-" + System.nanoTime(), "datos");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir", () -> DATA.toString().replace('\\', '/'));
        registry.add("app.printer.mode", () -> "fake");
        registry.add("app.printer.fake-dir", () -> "target/tickets-test");
        registry.add("app.fingerprint.mode", () -> "fake");
        registry.add("app.backup.on-startup", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Value("${app.backup.dir}") String backupDir;
    @Value("${server.address}") String address;

    @Test
    void arrancaEnCarpetaNuevaSinDatosDeEjemplo() {
        assertThat(DATA.resolve("monster-gym.db")).exists();
        assertThat(Path.of(backupDir)).isEqualTo(Path.of(DATA.toString(), "respaldos"));
        assertThat(address).isEqualTo("127.0.0.1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM employee", Integer.class)).isZero();
        // Los cuatro planes sí existen en producción (V3)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan", Integer.class)).isEqualTo(4);
    }
}

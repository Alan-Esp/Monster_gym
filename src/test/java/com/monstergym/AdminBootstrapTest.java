package com.monstergym;

import com.monstergym.auth.AdminBootstrap;
import com.monstergym.auth.EmployeeRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AdminBootstrapTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private EmployeeRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        Path db = Files.createTempFile(Files.createDirectories(Path.of("target")), "bootstrap-test-", ".db");
        Files.delete(db);
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.enforceForeignKeys(true);
        SQLiteDataSource sqlite = new SQLiteDataSource(cfg);
        sqlite.setUrl("jdbc:sqlite:" + db);
        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqlite);
        hikari.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(hikari);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repo = new EmployeeRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        dataSource.close();
    }

    private AdminBootstrap bootstrap(String username, String name, String pin) {
        return new AdminBootstrap(repo, encoder, username, name, pin);
    }

    private int admins() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE role = 'administrador'", Integer.class);
    }

    @Test
    void creaAdministradorDesdeVariablesDeEntorno() {
        assertThat(bootstrap("dueno", "Dueño del Gym", "s3cr3t0").createIfMissing()).isTrue();

        var admin = repo.findByUsername("dueno").orElseThrow();
        assertThat(admin.name()).isEqualTo("Dueño del Gym");
        assertThat(admin.active()).isTrue();
        assertThat(admin.pinHash()).isNotEqualTo("s3cr3t0");
        assertThat(encoder.matches("s3cr3t0", admin.pinHash())).isTrue();
    }

    @Test
    void noDuplicaSiYaExisteUnAdministrador() {
        bootstrap("dueno", "Dueño", "s3cr3t0").createIfMissing();
        assertThat(bootstrap("otro", "Otro", "s3cr3t0").createIfMissing()).isFalse();
        assertThat(admins()).isEqualTo(1);
    }

    @Test
    void sinVariables_noCreaNada() {
        assertThat(bootstrap("", "Administrador", "").createIfMissing()).isFalse();
        assertThat(bootstrap("dueno", "Administrador", "").createIfMissing()).isFalse();
        assertThat(admins()).isZero();
    }

    @Test
    void pinDemasiadoCorto_noCreaNada() {
        assertThat(bootstrap("dueno", "Administrador", "12").createIfMissing()).isFalse();
        assertThat(admins()).isZero();
    }

    @Test
    void nombreVacio_usaAdministradorPorDefecto() {
        bootstrap("dueno", " ", "s3cr3t0").createIfMissing();
        assertThat(repo.findByUsername("dueno").orElseThrow().name()).isEqualTo("Administrador");
    }
}

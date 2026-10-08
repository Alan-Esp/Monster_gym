package com.monstergym;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaAndViewTest {

    private static final Path DB_PATH = Path.of("target/test-schema.db");
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    void setUp() throws Exception {
        Files.deleteIfExists(DB_PATH);
        Files.deleteIfExists(Path.of("target/test-schema.db-wal"));
        Files.deleteIfExists(Path.of("target/test-schema.db-shm"));

        SQLiteConfig sqLiteConfig = new SQLiteConfig();
        sqLiteConfig.enforceForeignKeys(true);
        sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);

        SQLiteDataSource sqLiteDataSource = new SQLiteDataSource(sqLiteConfig);
        sqLiteDataSource.setUrl("jdbc:sqlite:" + DB_PATH);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDataSource(sqLiteDataSource);
        hikariConfig.setMaximumPoolSize(1);
        dataSource = new HikariDataSource(hikariConfig);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbc = new JdbcTemplate(dataSource);
        insertSharedFixtures();
    }

    @AfterAll
    void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    private void insertSharedFixtures() {
        // V3 ya crea los planes; las membresías de estas pruebas usan el plan con id 1
        jdbc.execute("""
                INSERT INTO employee (id, name, username, role, pin_hash)
                VALUES (1, 'Admin Test', 'admin', 'administrador', 'hash-placeholder')
                """);
        jdbc.execute("""
                INSERT INTO shift (id, employee_id, opened_at, opening_cash_cents)
                VALUES (1, 1, '2026-10-07T08:00:00', 50000)
                """);
    }

    private int insertMember(String number, String name) {
        jdbc.update("""
                INSERT INTO member (number, full_name, joined_on)
                VALUES (?, ?, date('now', 'localtime'))
                """, number, name);
        return jdbc.queryForObject(
                "SELECT id FROM member WHERE number = ?", Integer.class, number);
    }

    private int insertPayment(int memberId) {
        jdbc.update("""
                INSERT INTO payment (member_id, plan_id, amount_cents, method, shift_id, employee_id, paid_at)
                VALUES (?, 1, 45000, 'efectivo', 1, 1, datetime('now', 'localtime'))
                """, memberId);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Integer.class);
    }

    private int insertVoidedPayment(int memberId) {
        jdbc.update("""
                INSERT INTO payment (member_id, plan_id, amount_cents, method, shift_id, employee_id,
                                     paid_at, voided_at, void_reason)
                VALUES (?, 1, 45000, 'efectivo', 1, 1,
                        datetime('now', 'localtime'), datetime('now', 'localtime'), 'Error de cobro')
                """, memberId);
        return jdbc.queryForObject("SELECT last_insert_rowid()", Integer.class);
    }

    // ── 1. Todas las tablas existen ────────────────────────────────────────────

    @Test
    @Order(1)
    void todasLasTablas_existen() {
        List<String> tablas = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
                String.class);
        assertThat(tablas).containsAll(List.of(
                "employee", "flyway_schema_history", "member", "membership",
                "payment", "plan", "print_job", "product",
                "sale", "sale_item", "setting", "shift", "stock_movement", "visit"));
    }

    @Test
    @Order(2)
    void vista_vMemberStatus_existe() {
        String type = jdbc.queryForObject(
                "SELECT type FROM sqlite_master WHERE name = 'v_member_status'",
                String.class);
        assertThat(type).isEqualTo("view");
    }

    @Test
    @Order(2)
    void v3_creaLosCuatroPlanesConSusPrecios() {
        List<String> planes = jdbc.queryForList(
                "SELECT name || ':' || days || ':' || price_cents FROM plan WHERE active = 1 ORDER BY days",
                String.class);
        assertThat(planes).containsExactly(
                "Semanal:7:12000", "Quincenal:15:18000", "Mensual:30:30000", "Anual:365:300000");
    }

    // ── 2. Vista: membresía activa ─────────────────────────────────────────────

    @Test
    @Order(3)
    void vista_activa() {
        int mid = insertMember("9001", "Socio Activo");
        int pid = insertPayment(mid);
        jdbc.update("""
                INSERT INTO membership (member_id, plan_id, payment_id, starts_on, ends_on)
                VALUES (?, 1, ?, date('now', 'localtime'), date('now', 'localtime', '+10 days'))
                """, mid, pid);

        String status = jdbc.queryForObject(
                "SELECT status FROM v_member_status WHERE member_id = ?", String.class, mid);
        assertThat(status).isEqualTo("activa");
    }

    // ── 3. Vista: por vencer ───────────────────────────────────────────────────

    @Test
    @Order(4)
    void vista_porVencer() {
        int mid = insertMember("9002", "Socio Por Vencer");
        int pid = insertPayment(mid);
        jdbc.update("""
                INSERT INTO membership (member_id, plan_id, payment_id, starts_on, ends_on)
                VALUES (?, 1, ?, date('now', 'localtime'), date('now', 'localtime', '+2 days'))
                """, mid, pid);

        String status = jdbc.queryForObject(
                "SELECT status FROM v_member_status WHERE member_id = ?", String.class, mid);
        assertThat(status).isEqualTo("por_vencer");
    }

    // ── 4. Vista: borde exacto de warn_days (3 == warn_days → por_vencer) ─────

    @Test
    @Order(5)
    void vista_porVencer_bordeWarnDays() {
        int mid = insertMember("9003", "Socio Borde Warn");
        int pid = insertPayment(mid);
        jdbc.update("""
                INSERT INTO membership (member_id, plan_id, payment_id, starts_on, ends_on)
                VALUES (?, 1, ?, date('now', 'localtime'), date('now', 'localtime', '+3 days'))
                """, mid, pid);

        String status = jdbc.queryForObject(
                "SELECT status FROM v_member_status WHERE member_id = ?", String.class, mid);
        assertThat(status).isEqualTo("por_vencer");
    }

    // ── 5. Vista: vencida (ayer) ───────────────────────────────────────────────

    @Test
    @Order(6)
    void vista_vencida() {
        int mid = insertMember("9004", "Socio Vencido");
        int pid = insertPayment(mid);
        jdbc.update("""
                INSERT INTO membership (member_id, plan_id, payment_id, starts_on, ends_on)
                VALUES (?, 1, ?, date('now', 'localtime', '-30 days'), date('now', 'localtime', '-1 day'))
                """, mid, pid);

        String status = jdbc.queryForObject(
                "SELECT status FROM v_member_status WHERE member_id = ?", String.class, mid);
        assertThat(status).isEqualTo("vencida");
    }

    // ── 6. Vista: sin membresía → vencida ─────────────────────────────────────

    @Test
    @Order(7)
    void vista_sinMembresia_esVencida() {
        int mid = insertMember("9005", "Socio Sin Membresia");

        String status = jdbc.queryForObject(
                "SELECT status FROM v_member_status WHERE member_id = ?", String.class, mid);
        assertThat(status).isEqualTo("vencida");
    }

    // ── 7. Vista: pago anulado no cuenta, queda vencida ───────────────────────

    @Test
    @Order(8)
    void vista_pagoAnulado_noContabiliza() {
        int mid = insertMember("9006", "Socio Pago Anulado");
        int pid = insertVoidedPayment(mid);
        jdbc.update("""
                INSERT INTO membership (member_id, plan_id, payment_id, starts_on, ends_on)
                VALUES (?, 1, ?, date('now', 'localtime'), date('now', 'localtime', '+20 days'))
                """, mid, pid);

        String status = jdbc.queryForObject(
                "SELECT status FROM v_member_status WHERE member_id = ?", String.class, mid);
        assertThat(status).isEqualTo("vencida");
    }
}

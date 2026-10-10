package com.monstergym.backup;

import com.monstergym.common.ApiException;
import com.monstergym.fingerprint.FingerprintService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConfig;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Respaldos de la base SQLite.
 * <ul>
 * <li>Un respaldo por día: respaldos/monster-gym-AAAA-MM-DD.db (VACUUM INTO: copia consistente con la app
 *     en uso). Respaldar de nuevo el mismo día lo reemplaza. Se conservan los últimos app.backup.keep-days.</li>
 * <li>Restaurar reemplaza todos los datos: antes se valida el archivo y se guarda la base actual en
 *     antes-de-restaurar-AAAA-MM-DD-HHMMSS.db (se conservan los últimos 5).</li>
 * </ul>
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    static final Pattern DAILY = Pattern.compile("monster-gym-\\d{4}-\\d{2}-\\d{2}\\.db");
    static final Pattern PRE_RESTORE = Pattern.compile("antes-de-restaurar-\\d{4}-\\d{2}-\\d{2}-\\d{6}\\.db");
    static final int KEEP_PRE_RESTORE = 5;
    static final String CONFIRM_WORD = "RESTAURAR";
    private static final List<String> REQUIRED_TABLES =
            List.of("flyway_schema_history", "employee", "member", "payment", "shift", "setting");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final ZoneId MX = ZoneId.of("America/Mexico_City");

    /** kind: diario o antes_de_restaurar. createdAt: fecha y hora local del archivo. */
    public record BackupFile(String name, long sizeBytes, String createdAt, String kind) {
    }

    public record BackupList(String dir, int keepDays, List<BackupFile> items) {
    }

    /** safetyBackup: copia de la base que había antes de restaurar. */
    public record RestoreResult(String restoredFrom, String safetyBackup) {
    }

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final Flyway flyway;
    private final FingerprintService fingerprints;
    private final Clock clock;
    private final Path dir;
    private final int keepDays;

    public BackupService(JdbcTemplate jdbc, DataSource dataSource, Flyway flyway, FingerprintService fingerprints,
                         Clock clock, @Value("${app.backup.dir:respaldos}") String dir,
                         @Value("${app.backup.keep-days:30}") int keepDays) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.flyway = flyway;
        this.fingerprints = fingerprints;
        this.clock = clock;
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        this.keepDays = Math.max(keepDays, 1);
    }

    // ── Respaldo ──────────────────────────────────────────────────────────────

    /** Respaldo del día (reemplaza el de hoy si ya existía) y rotación de los viejos. */
    public synchronized BackupFile backupToday() {
        Path target = dir.resolve(dailyName(LocalDate.now(clock)));
        snapshot(target);
        rotate();
        log.info("Respaldo creado: {}", target);
        return describe(target);
    }

    /** Al arrancar: si la PC estuvo apagada a la hora del respaldo, hoy todavía no hay uno. */
    public synchronized boolean backupTodayIfMissing() {
        if (Files.exists(dir.resolve(dailyName(LocalDate.now(clock))))) return false;
        backupToday();
        return true;
    }

    public BackupList list() {
        List<BackupFile> items = files().stream()
                .map(this::describe)
                .sorted(Comparator.comparing(BackupFile::createdAt).reversed())
                .toList();
        return new BackupList(dir.toString(), keepDays, items);
    }

    /** Archivo de respaldo por nombre; solo nombres generados por la app (sin rutas). */
    public Path resolve(String name) {
        if (name == null || !(DAILY.matcher(name).matches() || PRE_RESTORE.matcher(name).matches())) {
            throw ApiException.notFound("No se encontró el respaldo.");
        }
        Path file = dir.resolve(name);
        if (!Files.isRegularFile(file)) throw ApiException.notFound("No se encontró el respaldo.");
        return file;
    }

    // ── Restauración ──────────────────────────────────────────────────────────

    /**
     * Reemplaza la base con el archivo subido. Solo sin turnos abiertos y con la palabra de confirmación.
     * Si algo falla después de reemplazar, se vuelve a la base anterior.
     */
    public synchronized RestoreResult restore(InputStream upload, String originalName, String confirm) {
        if (!CONFIRM_WORD.equals(confirm == null ? null : confirm.trim())) {
            throw ApiException.invalidField("confirm", "Escribe " + CONFIRM_WORD + " para confirmar.");
        }
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM shift WHERE closed_at IS NULL", Integer.class);
        if (open != null && open > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "TURNO_ABIERTO",
                    "Cierra todos los turnos abiertos antes de restaurar un respaldo.");
        }
        Path temp = null;
        try {
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, "subida-", ".tmp");
            Files.copy(upload, temp, StandardCopyOption.REPLACE_EXISTING);
            validate(temp);

            Path safety = dir.resolve("antes-de-restaurar-" + LocalDateTime.now(clock).format(STAMP) + ".db");
            snapshot(safety);
            try {
                restoreFrom(temp);
                flyway.migrate();  // un respaldo de una versión anterior queda al día
            } catch (RuntimeException e) {
                log.error("Falló la restauración; se regresa a la base anterior", e);
                restoreFrom(safety);
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESTAURACION_FALLIDA",
                        "No se pudo restaurar el respaldo. La base quedó como estaba.");
            } finally {
                fingerprints.reload();
            }
            rotate();
            log.warn("Base restaurada desde {} (copia previa: {})", originalName, safety.getFileName());
            return new RestoreResult(originalName, safety.getFileName().toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("No se pudo borrar el archivo temporal {}", temp);
                }
            }
        }
    }

    /** Que sea una base SQLite sana de Monster Gym y no de una versión más nueva que esta app. */
    void validate(Path file) {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file, config.toProperties());
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) throw invalidFile("El archivo está dañado.");
            }
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            if (!tables.containsAll(REQUIRED_TABLES)) {
                throw invalidFile("El archivo no es un respaldo de Monster Gym.");
            }
            MigrationInfo current = flyway.info().current();
            try (ResultSet rs = st.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL")) {
                while (rs.next()) {
                    MigrationVersion v = MigrationVersion.fromVersion(rs.getString(1));
                    if (current != null && v.compareTo(current.getVersion()) > 0) {
                        throw invalidFile("El respaldo es de una versión más nueva del sistema.");
                    }
                }
            }
        } catch (SQLException e) {
            throw invalidFile("El archivo no es una base de datos válida.");
        }
    }

    private static ApiException invalidFile(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESPALDO_INVALIDO", message);
    }

    /** Copia las páginas del archivo sobre la base en uso (API de respaldo de SQLite, en una sola conexión). */
    private void restoreFrom(Path file) {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("restore from '" + sqlPath(file) + "'");
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo restaurar desde " + file, e);
        }
    }

    // ── Archivos ──────────────────────────────────────────────────────────────

    /** VACUUM INTO no sobrescribe: se escribe a un temporal y se mueve encima. */
    private void snapshot(Path target) {
        try {
            Files.createDirectories(dir);
            Path temp = dir.resolve(target.getFileName() + ".tmp");
            Files.deleteIfExists(temp);
            jdbc.update("VACUUM INTO ?", temp.toString());
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo crear el respaldo " + target, e);
        }
    }

    /** Conserva los últimos keepDays respaldos diarios y los últimos 5 previos a restaurar. */
    private void rotate() {
        List<Path> all = files();
        prune(all.stream().filter(p -> DAILY.matcher(p.getFileName().toString()).matches()).toList(), keepDays);
        prune(all.stream().filter(p -> PRE_RESTORE.matcher(p.getFileName().toString()).matches()).toList(),
                KEEP_PRE_RESTORE);
    }

    /** Los nombres llevan la fecha, así que el orden alfabético es el cronológico. */
    private void prune(List<Path> files, int keep) {
        files.stream()
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                .skip(keep)
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                        log.info("Respaldo viejo borrado: {}", p.getFileName());
                    } catch (IOException e) {
                        log.warn("No se pudo borrar el respaldo viejo {}", p, e);
                    }
                });
    }

    private List<Path> files() {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.db")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (DAILY.matcher(n).matches() || PRE_RESTORE.matcher(n).matches()) out.add(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private BackupFile describe(Path p) {
        try {
            String name = p.getFileName().toString();
            LocalDateTime modified = LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), MX)
                    .truncatedTo(ChronoUnit.SECONDS);
            return new BackupFile(name, Files.size(p), modified.toString(),
                    DAILY.matcher(name).matches() ? "diario" : "antes_de_restaurar");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String dailyName(LocalDate date) {
        return "monster-gym-" + date + ".db";
    }

    private static String sqlPath(Path file) {
        String s = file.toAbsolutePath().toString().replace('\\', '/');
        if (s.contains("'")) throw new IllegalStateException("La ruta de respaldos no puede llevar comillas: " + s);
        return s;
    }
}

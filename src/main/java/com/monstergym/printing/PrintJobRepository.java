package com.monstergym.printing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Optional;

@Repository
public class PrintJobRepository {

    private static final String SELECT = """
            SELECT id, kind, ref_id, is_copy, status, transport, attempts, error, employee_id, created_at, finished_at
            FROM print_job
            """;

    private static final RowMapper<PrintJob> MAPPER = (rs, i) -> new PrintJob(
            rs.getLong("id"),
            rs.getString("kind"),
            rs.getObject("ref_id") == null ? null : rs.getLong("ref_id"),
            rs.getInt("is_copy") == 1,
            rs.getString("status"),
            rs.getString("transport"),
            rs.getInt("attempts"),
            rs.getString("error"),
            rs.getLong("employee_id"),
            rs.getString("created_at"),
            rs.getString("finished_at"));

    private final JdbcTemplate jdbc;

    public PrintJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String kind, Long refId, boolean copy, long employeeId, String createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO print_job (kind, ref_id, is_copy, status, employee_id, created_at) "
                            + "VALUES (?, ?, ?, 'pendiente', ?, ?)", new String[]{"id"});
            ps.setString(1, kind);
            if (refId == null) ps.setNull(2, Types.INTEGER); else ps.setLong(2, refId);
            ps.setInt(3, copy ? 1 : 0);
            ps.setLong(4, employeeId);
            ps.setString(5, createdAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<PrintJob> findById(long id) {
        return jdbc.query(SELECT + " WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** El trabajo pendiente más antiguo: se imprimen en el orden en que se pidieron. */
    public Optional<PrintJob> nextPending() {
        return jdbc.query(SELECT + " WHERE status = 'pendiente' ORDER BY id LIMIT 1", MAPPER).stream().findFirst();
    }

    public void markSending(long id, int attempts, String transport) {
        jdbc.update("UPDATE print_job SET status = 'enviando', attempts = ?, transport = ? WHERE id = ?",
                attempts, transport, id);
    }

    /** Intento fallido que se va a reintentar: queda "enviando" con el error para que la pantalla lo vea. */
    public void recordError(long id, String error) {
        jdbc.update("UPDATE print_job SET error = ? WHERE id = ?", error, id);
    }

    public void finish(long id, String status, String error, String finishedAt) {
        jdbc.update("UPDATE print_job SET status = ?, error = ?, finished_at = ? WHERE id = ?",
                status, error, finishedAt, id);
    }

    /** Al arrancar: lo que quedó a medias no se imprime por sorpresa horas después. */
    public int failUnfinished(String error, String finishedAt) {
        return jdbc.update("UPDATE print_job SET status = 'fallido', error = ?, finished_at = ? "
                + "WHERE status IN ('pendiente', 'enviando')", error, finishedAt);
    }

    public int countUnfinished() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM print_job WHERE status IN ('pendiente', 'enviando')", Integer.class);
    }

    public int countFailedSince(String since) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM print_job WHERE status = 'fallido' AND created_at >= ?", Integer.class, since);
    }

    public Optional<PrintJob> lastFinished() {
        return jdbc.query(SELECT + " WHERE finished_at IS NOT NULL ORDER BY id DESC LIMIT 1", MAPPER)
                .stream().findFirst();
    }
}

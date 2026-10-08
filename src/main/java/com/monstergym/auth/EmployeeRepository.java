package com.monstergym.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class EmployeeRepository {

    private static final RowMapper<Employee> MAPPER = (rs, i) -> new Employee(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("username"),
            Role.fromDb(rs.getString("role")),
            rs.getString("shift_label"),
            rs.getString("pin_hash"),
            rs.getInt("failed_attempts"),
            rs.getString("locked_until"),
            rs.getInt("active") == 1);

    private final JdbcTemplate jdbc;

    public EmployeeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Employee> findByUsername(String username) {
        return jdbc.query("SELECT * FROM employee WHERE username = ?", MAPPER, username)
                .stream().findFirst();
    }

    public void recordFailedAttempt(long id, int failedAttempts, String lockedUntil) {
        jdbc.update("UPDATE employee SET failed_attempts = ?, locked_until = ? WHERE id = ?",
                failedAttempts, lockedUntil, id);
    }

    public void resetFailedAttempts(long id) {
        jdbc.update("UPDATE employee SET failed_attempts = 0, locked_until = NULL WHERE id = ?", id);
    }

    public boolean existsActiveAdmin() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE role = 'administrador' AND active = 1", Integer.class);
        return n != null && n > 0;
    }

    public boolean existsUsername(String username) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE username = ?", Integer.class, username);
        return n != null && n > 0;
    }

    public void insert(String name, String username, Role role, String pinHash) {
        insert(name, username, role, null, pinHash);
    }

    public long insert(String name, String username, Role role, String shiftLabel, String pinHash) {
        jdbc.update("INSERT INTO employee (name, username, role, shift_label, pin_hash) VALUES (?, ?, ?, ?, ?)",
                name, username, role.dbValue(), shiftLabel, pinHash);
        return jdbc.queryForObject("SELECT id FROM employee WHERE username = ?", Long.class, username);
    }

    public Optional<Employee> findById(long id) {
        return jdbc.query("SELECT * FROM employee WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** Activos primero, luego por nombre. */
    public java.util.List<Employee> findAll() {
        return jdbc.query("SELECT * FROM employee ORDER BY active DESC, name", MAPPER);
    }

    public boolean usernameTakenByOther(String username, long id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE username = ? AND id <> ?", Integer.class, username, id);
        return n != null && n > 0;
    }

    public int countActiveAdminsExcept(long id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE role = 'administrador' AND active = 1 AND id <> ?",
                Integer.class, id);
        return n == null ? 0 : n;
    }

    public void update(long id, String name, String username, Role role, String shiftLabel, boolean active) {
        jdbc.update("UPDATE employee SET name = ?, username = ?, role = ?, shift_label = ?, active = ? WHERE id = ?",
                name, username, role.dbValue(), shiftLabel, active ? 1 : 0, id);
    }

    /** Nuevo PIN; también quita el bloqueo por intentos fallidos. */
    public void updatePin(long id, String pinHash) {
        jdbc.update("UPDATE employee SET pin_hash = ?, failed_attempts = 0, locked_until = NULL WHERE id = ?",
                pinHash, id);
    }
}

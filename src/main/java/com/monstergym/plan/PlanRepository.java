package com.monstergym.plan;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PlanRepository {

    private static final RowMapper<Plan> MAPPER = (rs, i) -> new Plan(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getInt("days"),
            rs.getLong("price_cents"),
            rs.getInt("active") == 1);

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Plan> findActive() {
        return jdbc.query("SELECT * FROM plan WHERE active = 1 ORDER BY days", MAPPER);
    }

    public Optional<Plan> findById(long id) {
        return jdbc.query("SELECT * FROM plan WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public void updatePrice(long id, long priceCents) {
        jdbc.update("UPDATE plan SET price_cents = ? WHERE id = ?", priceCents, id);
    }
}

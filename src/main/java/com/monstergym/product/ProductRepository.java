package com.monstergym.product;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ProductRepository {

    public record Product(long id, String name, String size, long priceCents, int stock, int capacity,
                          int minStock, boolean active) {
    }

    /** Producto con lo vendido en el rango (ventas no canceladas). */
    public record ProductWithSales(Product product, int soldQty, long soldCents) {
    }

    private static final RowMapper<Product> MAPPER = (rs, i) -> new Product(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("size"),
            rs.getLong("price_cents"),
            rs.getInt("stock"),
            rs.getInt("capacity"),
            rs.getInt("min_stock"),
            rs.getInt("active") == 1);

    private final JdbcTemplate jdbc;

    public ProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Productos activos con lo vendido entre from y to (texto ISO, [from, to)). */
    public List<ProductWithSales> findActiveWithSales(String from, String to) {
        return jdbc.query("""
                SELECT p.*, COALESCE(t.qty, 0) AS sold_qty, COALESCE(t.cents, 0) AS sold_cents
                FROM product p
                LEFT JOIN (
                    SELECT si.product_id, SUM(si.qty) AS qty, SUM(si.qty * si.unit_price_cents) AS cents
                    FROM sale_item si JOIN sale s ON s.id = si.sale_id
                    WHERE s.voided_at IS NULL AND s.created_at >= ? AND s.created_at < ?
                    GROUP BY si.product_id
                ) t ON t.product_id = p.id
                WHERE p.active = 1
                ORDER BY p.name
                """, (rs, i) -> new ProductWithSales(MAPPER.mapRow(rs, i), rs.getInt("sold_qty"), rs.getLong("sold_cents")),
                from, to);
    }

    public Optional<Product> findById(long id) {
        return jdbc.query("SELECT * FROM product WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * Descuenta stock solo si alcanza (una sola sentencia: dos ventas simultáneas no pueden dejarlo negativo).
     * Devuelve false si no había suficiente.
     */
    public boolean takeStock(long id, int qty) {
        return jdbc.update("UPDATE product SET stock = stock - ? WHERE id = ? AND active = 1 AND stock >= ?",
                qty, id, qty) == 1;
    }

    /** Suma (o resta, si delta < 0) stock sin dejarlo negativo. Devuelve false si quedaría negativo. */
    public boolean addStock(long id, int delta) {
        return jdbc.update("UPDATE product SET stock = stock + ? WHERE id = ? AND stock + ? >= 0",
                delta, id, delta) == 1;
    }

    public void insertMovement(long productId, int delta, String reason, long employeeId, Long saleId,
                               String createdAt) {
        jdbc.update("""
                INSERT INTO stock_movement (product_id, delta, reason, employee_id, sale_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, productId, delta, reason, employeeId, saleId, createdAt);
    }
}

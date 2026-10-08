package com.monstergym.product;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.common.Times;
import com.monstergym.product.ProductRepository.Product;
import com.monstergym.product.ProductRepository.ProductWithSales;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProductService {

    /**
     * Producto para el Refri. lowStock: stock <= min_stock (incluye agotados); outOfStock: stock = 0.
     * soldToday y soldTodayCents salen de las ventas de hoy no canceladas.
     */
    public record ProductItem(long id, String name, String size, long priceCents, int stock, int capacity,
                              int minStock, int soldToday, long soldTodayCents, boolean lowStock,
                              boolean outOfStock) {
    }

    private final ProductRepository products;
    private final Clock clock;

    public ProductService(ProductRepository products, Clock clock) {
        this.products = products;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProductItem> list() {
        LocalDate today = LocalDate.now(clock);
        return products.findActiveWithSales(today.toString(), today.plusDays(1).toString()).stream()
                .map(ProductService::item).toList();
    }

    /**
     * Movimiento manual de inventario: reabasto suma qty, merma resta qty, ajuste suma qty con su signo.
     * El stock nunca queda negativo.
     */
    @Transactional
    public ProductItem adjust(EmployeePrincipal employee, long productId, int qty, String reason) {
        Product p = products.findById(productId)
                .filter(Product::active)
                .orElseThrow(() -> ApiException.notFound("No se encontró el producto."));
        if (!"ajuste".equals(reason) && qty <= 0) {
            throw ApiException.invalidField("qty", "La cantidad debe ser mayor a cero.");
        }
        int delta = "merma".equals(reason) ? -qty : qty;
        if (!products.addStock(productId, delta)) {
            throw new ApiException(HttpStatus.CONFLICT, "STOCK_INSUFICIENTE",
                    "No hay suficiente " + p.name() + " para descontar: quedan " + p.stock() + ".");
        }
        products.insertMovement(productId, delta, reason, employee.id(), null, Times.format(LocalDateTime.now(clock)));
        return list().stream().filter(i -> i.id() == productId).findFirst().orElseThrow();
    }

    private static ProductItem item(ProductWithSales ps) {
        Product p = ps.product();
        return new ProductItem(p.id(), p.name(), p.size(), p.priceCents(), p.stock(), p.capacity(), p.minStock(),
                ps.soldQty(), ps.soldCents(), p.stock() <= p.minStock(), p.stock() == 0);
    }
}

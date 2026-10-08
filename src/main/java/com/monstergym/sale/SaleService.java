package com.monstergym.sale;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.common.Times;
import com.monstergym.product.ProductRepository;
import com.monstergym.product.ProductRepository.Product;
import com.monstergym.shift.Shift;
import com.monstergym.shift.ShiftRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SaleService {

    public record Line(long productId, int qty) {
    }

    private final SaleRepository sales;
    private final ProductRepository products;
    private final ShiftRepository shifts;
    private final Clock clock;

    public SaleService(SaleRepository sales, ProductRepository products, ShiftRepository shifts, Clock clock) {
        this.sales = sales;
        this.products = products;
        this.shifts = shifts;
        this.clock = clock;
    }

    /**
     * Una venta es una sola transacción: valida y descuenta el stock de cada producto, guarda el precio
     * unitario del momento y la liga al turno abierto. Si un producto no alcanza, no se guarda nada.
     */
    @Transactional
    public SaleReceipt create(EmployeePrincipal employee, String method, List<Line> lines) {
        Shift shift = shifts.findOpenByEmployee(employee.id()).orElseThrow(ApiException::sinTurnoParaCobrar);

        // El mismo producto en dos líneas se suma
        Map<Long, Integer> qtyByProduct = new LinkedHashMap<>();
        lines.forEach(l -> qtyByProduct.merge(l.productId(), l.qty(), Integer::sum));

        String now = Times.format(LocalDateTime.now(clock));
        Map<Product, Integer> taken = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<Long, Integer> e : qtyByProduct.entrySet()) {
            Product p = products.findById(e.getKey())
                    .filter(Product::active)
                    .orElseThrow(() -> ApiException.invalidField("items", "Uno de los productos ya no existe."));
            int qty = e.getValue();
            // La condición stock >= qty va en el mismo UPDATE: si otra venta se llevó el último, no procede
            if (!products.takeStock(p.id(), qty)) {
                int left = products.findById(p.id()).map(Product::stock).orElse(0);
                throw new ApiException(HttpStatus.CONFLICT, "STOCK_INSUFICIENTE", left == 0
                        ? p.name() + " está agotado."
                        : "No hay suficiente " + p.name() + ": quedan " + left + " y pediste " + qty + ".");
            }
            taken.put(p, qty);
            total += p.priceCents() * qty;
        }

        long saleId = sales.insertSale(shift.id(), employee.id(), total, method, now);
        taken.forEach((p, qty) -> {
            sales.insertItem(saleId, p.id(), qty, p.priceCents());
            products.insertMovement(p.id(), -qty, "venta", employee.id(), saleId, now);
        });
        return find(saleId);
    }

    @Transactional(readOnly = true)
    public List<SaleReceipt> currentShift(EmployeePrincipal employee, int limit) {
        Shift shift = shifts.findOpenByEmployee(employee.id()).orElseThrow(ApiException::sinTurno);
        return sales.findByShift(shift.id(), limit).stream().map(s -> withItems(s)).toList();
    }

    /** Cancela la venta y regresa el stock (movimiento "ajuste"). Solo si su turno sigue abierto. */
    @Transactional
    public SaleReceipt voidSale(EmployeePrincipal employee, long saleId, String reason) {
        SaleReceipt sale = sales.findById(saleId)
                .orElseThrow(() -> ApiException.notFound("No se encontró la venta."));
        if (sale.voidedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "VENTA_CANCELADA", "La venta ya estaba cancelada.");
        }
        if (!shifts.findById(sale.shiftId()).map(Shift::isOpen).orElse(false)) {
            throw new ApiException(HttpStatus.CONFLICT, "TURNO_CERRADO",
                    "No se puede cancelar una venta de un turno ya cerrado.");
        }
        String now = Times.format(LocalDateTime.now(clock));
        sales.voidSale(saleId, now, reason.trim());
        for (SaleReceipt.Item item : sales.items(saleId)) {
            products.addStock(item.productId(), item.qty());
            products.insertMovement(item.productId(), item.qty(), "ajuste", employee.id(), null, now);
        }
        return find(saleId);
    }

    private SaleReceipt find(long saleId) {
        return withItems(sales.findById(saleId).orElseThrow());
    }

    private SaleReceipt withItems(SaleReceipt s) {
        return new SaleReceipt(s.id(), s.folio(), s.createdAt(), s.method(), s.totalCents(), s.employeeName(),
                s.shiftId(), s.voidedAt(), s.voidReason(), sales.items(s.id()));
    }
}

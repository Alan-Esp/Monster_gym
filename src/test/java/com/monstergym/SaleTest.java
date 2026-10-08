package com.monstergym;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SaleTest extends ApiTestBase {

    private static final long AGUA = 1, PROTEINA = 2, JUGO = 3;

    @BeforeEach
    void productos() {
        jdbc.update("INSERT INTO product (id, name, size, price_cents, stock, capacity, min_stock) VALUES "
                + "(1, 'Agua natural', '1 L', 1500, 10, 24, 5), "
                + "(2, 'Proteína lista', '330 ml', 5500, 2, 12, 3), "
                + "(3, 'Jugo verde', '500 ml', 4500, 0, 10, 3)");
    }

    private ResultActions vender(MockHttpSession s, String method, Object... productQty) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < productQty.length; i += 2) {
            items.add(Map.of("productId", productQty[i], "qty", productQty[i + 1]));
        }
        return mvc.perform(post("/api/sales").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("method", method, "items", items))));
    }

    private ResultActions movimiento(MockHttpSession s, long productId, int qty, String reason) throws Exception {
        return mvc.perform(post("/api/products/" + productId + "/restock").session(s).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("qty", qty, "reason", reason))));
    }

    private int stock(long productId) {
        return jdbc.queryForObject("SELECT stock FROM product WHERE id = ?", Integer.class, productId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    // ── Productos ──────────────────────────────────────────────────────────────

    @Test
    void productos_conBanderasDeStockYVendidoHoy() throws Exception {
        vender(loginWithShift("recepcion", 0), "efectivo", AGUA, 3).andExpect(status().isCreated());
        mvc.perform(get("/api/products").session(loginAs("entrenador")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Agua natural"))
                .andExpect(jsonPath("$[0].stock").value(7))
                .andExpect(jsonPath("$[0].capacity").value(24))
                .andExpect(jsonPath("$[0].soldToday").value(3))
                .andExpect(jsonPath("$[0].soldTodayCents").value(4500))
                .andExpect(jsonPath("$[0].lowStock").value(false))
                .andExpect(jsonPath("$[1].name").value("Jugo verde"))
                .andExpect(jsonPath("$[1].outOfStock").value(true))
                .andExpect(jsonPath("$[1].lowStock").value(true))
                .andExpect(jsonPath("$[2].name").value("Proteína lista"))
                .andExpect(jsonPath("$[2].lowStock").value(true))  // 2 <= min 3
                .andExpect(jsonPath("$[2].outOfStock").value(false));
    }

    // ── Venta ──────────────────────────────────────────────────────────────────

    @Test
    void venta_descuentaStockGuardaPrecioYLigaAlTurno() throws Exception {
        MockHttpSession s = loginWithShift("recepcion", 0);
        long shiftId = jdbc.queryForObject("SELECT id FROM shift WHERE employee_id = 2", Long.class);
        vender(s, "tarjeta", AGUA, 2, PROTEINA, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCents").value(8500))
                .andExpect(jsonPath("$.method").value("tarjeta"))
                .andExpect(jsonPath("$.shiftId").value(shiftId))
                .andExpect(jsonPath("$.employeeName").value("Rocío Recepción"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].unitPriceCents").value(1500))
                .andExpect(jsonPath("$.items[0].subtotalCents").value(3000));
        assertThat(stock(AGUA)).isEqualTo(8);
        assertThat(stock(PROTEINA)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT product_id || ':' || delta || ':' || reason FROM stock_movement ORDER BY id",
                String.class)).containsExactly("1:-2:venta", "2:-1:venta");
    }

    @Test
    void venta_stockInsuficiente_noGuardaNada() throws Exception {
        vender(loginWithShift("recepcion", 0), "efectivo", AGUA, 2, PROTEINA, 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("STOCK_INSUFICIENTE"))
                .andExpect(jsonPath("$.error.message").value("No hay suficiente Proteína lista: quedan 2 y pediste 3."));
        // El agua ya se había descontado dentro de la transacción: se revierte
        assertThat(stock(AGUA)).isEqualTo(10);
        assertThat(stock(PROTEINA)).isEqualTo(2);
        assertThat(count("sale")).isZero();
        assertThat(count("sale_item")).isZero();
        assertThat(count("stock_movement")).isZero();
    }

    @Test
    void venta_productoAgotado() throws Exception {
        vender(loginWithShift("recepcion", 0), "efectivo", JUGO, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value("Jugo verde está agotado."));
    }

    @Test
    void venta_productoRepetido_seSuma() throws Exception {
        MockHttpSession s = loginWithShift("recepcion", 0);
        vender(s, "efectivo", PROTEINA, 1, PROTEINA, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].qty").value(2));
        assertThat(stock(PROTEINA)).isZero();
        // 1 + 2 sumarían 3 > 2: se rechaza aunque cada línea por separado alcanzaría
        jdbc.update("UPDATE product SET stock = 2 WHERE id = 2");
        vender(s, "efectivo", PROTEINA, 1, PROTEINA, 2).andExpect(status().isConflict());
    }

    @Test
    void venta_sinTurno() throws Exception {
        vender(loginAs("recepcion"), "efectivo", AGUA, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SIN_TURNO"));
        assertThat(stock(AGUA)).isEqualTo(10);
    }

    @Test
    void venta_guardaElPrecioDelMomento() throws Exception {
        MockHttpSession s = loginWithShift("recepcion", 0);
        vender(s, "efectivo", AGUA, 1);
        jdbc.update("UPDATE product SET price_cents = 2000 WHERE id = 1");
        vender(s, "efectivo", AGUA, 1).andExpect(jsonPath("$.totalCents").value(2000));
        assertThat(jdbc.queryForList("SELECT unit_price_cents FROM sale_item ORDER BY id", Long.class))
                .containsExactly(1500L, 2000L);
    }

    @Test
    void venta_validaciones() throws Exception {
        MockHttpSession s = loginWithShift("recepcion", 0);
        mvc.perform(post("/api/sales").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("method", "efectivo", "items", List.of()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.items").exists());
        vender(s, "efectivo", AGUA, 0).andExpect(status().isUnprocessableEntity());
        vender(s, "cheque", AGUA, 1).andExpect(status().isUnprocessableEntity());
        vender(s, "efectivo", 99L, 1).andExpect(status().isUnprocessableEntity());
        assertThat(count("sale")).isZero();
    }

    /** Dos cajas venden al mismo tiempo la última Proteína: solo una venta procede. */
    @Test
    void ventasSimultaneas_delUltimoProducto_soloUnaProcede() throws Exception {
        jdbc.update("UPDATE product SET stock = 1 WHERE id = 2");
        MockHttpSession recepcion = loginWithShift("recepcion", 0);
        MockHttpSession admin = loginWithShift("admin", 0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> statuses = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (MockHttpSession s : List.of(recepcion, admin)) {
            futures.add(pool.submit(() -> {
                start.await();
                statuses.add(vender(s, "efectivo", PROTEINA, 1).andReturn().getResponse().getStatus());
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(stock(PROTEINA)).isZero();
        assertThat(count("sale")).isEqualTo(1);
        assertThat(count("stock_movement")).isEqualTo(1);
    }

    // ── Cancelación ────────────────────────────────────────────────────────────

    @Test
    void cancelar_regresaElStock() throws Exception {
        vender(loginWithShift("recepcion", 0), "efectivo", AGUA, 3);
        long saleId = jdbc.queryForObject("SELECT id FROM sale", Long.class);
        MockHttpSession admin = loginAs("admin");
        String url = "/api/sales/" + saleId + "/void";

        mvc.perform(post(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("reason", "Se arrepintió"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voidReason").value("Se arrepintió"));
        assertThat(stock(AGUA)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT reason || ':' || delta FROM stock_movement ORDER BY id DESC LIMIT 1",
                String.class)).isEqualTo("ajuste:3");

        mvc.perform(post(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("reason", "Otra vez"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VENTA_CANCELADA"));
        assertThat(stock(AGUA)).isEqualTo(10);
    }

    @Test
    void cancelar_ventaDeTurnoCerrado_noSePermite() throws Exception {
        MockHttpSession s = loginWithShift("recepcion", 0);
        vender(s, "efectivo", AGUA, 1);
        mvc.perform(post("/api/shifts/close").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("countedCashCents", 1500)))).andExpect(status().isOk());
        long saleId = jdbc.queryForObject("SELECT id FROM sale", Long.class);
        mvc.perform(post("/api/sales/" + saleId + "/void").session(loginAs("admin")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("reason", "Error"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TURNO_CERRADO"));
    }

    // ── Reabasto, merma y ajuste ───────────────────────────────────────────────

    @Test
    void movimientosDeInventario() throws Exception {
        MockHttpSession s = loginAs("recepcion");
        movimiento(s, AGUA, 6, "reabasto").andExpect(status().isOk()).andExpect(jsonPath("$.stock").value(16));
        movimiento(s, AGUA, 2, "merma").andExpect(jsonPath("$.stock").value(14));
        movimiento(s, AGUA, -4, "ajuste").andExpect(jsonPath("$.stock").value(10));
        movimiento(s, AGUA, 11, "merma")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message").value(containsString("quedan 10")));
        movimiento(s, AGUA, -3, "reabasto").andExpect(status().isUnprocessableEntity());
        movimiento(s, AGUA, 0, "ajuste").andExpect(status().isUnprocessableEntity());
        movimiento(s, AGUA, 1, "regalo").andExpect(status().isUnprocessableEntity());
        assertThat(stock(AGUA)).isEqualTo(10);
        assertThat(jdbc.queryForList("SELECT reason || ':' || delta || ':' || employee_id FROM stock_movement ORDER BY id",
                String.class)).containsExactly("reabasto:6:2", "merma:-2:2", "ajuste:-4:2");
    }

    // ── Permisos ───────────────────────────────────────────────────────────────

    @Test
    void permisosPorRol() throws Exception {
        MockHttpSession entrenador = loginAs("entrenador");
        mvc.perform(get("/api/products").session(entrenador)).andExpect(status().isOk());
        vender(entrenador, "efectivo", AGUA, 1).andExpect(status().isForbidden());
        movimiento(entrenador, AGUA, 1, "reabasto").andExpect(status().isForbidden());
        mvc.perform(get("/api/sales").session(entrenador)).andExpect(status().isForbidden());

        MockHttpSession recepcion = loginWithShift("recepcion", 0);
        vender(recepcion, "efectivo", AGUA, 1).andExpect(status().isCreated());
        long saleId = jdbc.queryForObject("SELECT id FROM sale", Long.class);
        mvc.perform(post("/api/sales/" + saleId + "/void").session(recepcion).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(Map.of("reason", "x"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/sales?shift=current").session(recepcion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].items[0].name").value("Agua natural"));
    }

    // ── Corte de caja con membresías + refri ───────────────────────────────────

    @Test
    void corteDelTurno_incluyeMembresiasYRefri() throws Exception {
        insertMember(1, "0001", "Socio Uno", null, null);
        MockHttpSession s = loginWithShift("recepcion", 50000);
        mvc.perform(post("/api/payments").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("memberId", 1, "planId", 3, "method", "efectivo")))).andExpect(status().isCreated());
        vender(s, "efectivo", AGUA, 2).andExpect(status().isCreated());
        vender(s, "tarjeta", PROTEINA, 1).andExpect(status().isCreated());

        mvc.perform(post("/api/shifts/close").session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("countedCashCents", 82500, "notes", "  Faltó cambio de $5  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberships.totalCents").value(30000))
                .andExpect(jsonPath("$.fridge.totalCents").value(8500))
                .andExpect(jsonPath("$.fridge.units").value(3))
                .andExpect(jsonPath("$.byMethod.efectivo").value(33000))
                .andExpect(jsonPath("$.byMethod.tarjeta").value(5500))
                .andExpect(jsonPath("$.totalCents").value(38500))
                .andExpect(jsonPath("$.expectedCashCents").value(83000))
                .andExpect(jsonPath("$.countedCashCents").value(82500))
                .andExpect(jsonPath("$.differenceCents").value(-500))
                .andExpect(jsonPath("$.notes").value("Faltó cambio de $5"));
    }
}

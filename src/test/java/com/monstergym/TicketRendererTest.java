package com.monstergym;

import com.monstergym.payment.PaymentReceipt;
import com.monstergym.printing.Ticket;
import com.monstergym.printing.TicketRenderer;
import com.monstergym.printing.TicketRenderer.Context;
import com.monstergym.printing.TicketRenderer.Copy;
import com.monstergym.shift.ShiftSummary;
import com.monstergym.shift.ShiftSummary.Fridge;
import com.monstergym.shift.ShiftSummary.Memberships;
import com.monstergym.shift.ShiftSummary.PlanLine;
import com.monstergym.shift.ShiftSummary.ProductLine;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Formato de los tickets a 32 columnas (PT-210, fuente A). */
class TicketRendererTest {

    private static final int W = 32;
    private static final Context ORIGINAL = new Context("Monster Gym", "Gracias por entrenar con nosotros.", W, null);
    private static final Context COPIA = new Context("Monster Gym", "Gracias por entrenar con nosotros.", W,
            new Copy("2026-10-09T10:15:00", "Admin Prueba"));

    private static PaymentReceipt payment(String memberName, String planName) {
        return new PaymentReceipt(123, "000123", new PaymentReceipt.Member(7, "0412", memberName),
                new PaymentReceipt.Plan(3, planName, 30), "2026-10-08", "2026-11-07", 135000, "efectivo",
                "Rocío Recepción", "2026-10-08T16:38:12", 5, null, null);
    }

    private static ShiftSummary shift() {
        Map<String, Long> byMethod = new LinkedHashMap<>();
        byMethod.put("efectivo", 120000L);
        byMethod.put("tarjeta", 30000L);
        byMethod.put("transferencia", 0L);
        var memberships = new Memberships(4, 120000, byMethod, List.of(
                new PlanLine("Mensual", 3, 90000), new PlanLine("Quincenal", 1, 18000)));
        var fridge = new Fridge(3, 7, 30000, byMethod, List.of(
                new ProductLine("Bebida isotónica sabor uva edición especial 1 L", 5, 20000),
                new ProductLine("Agua 600 ml", 2, 10000)));
        return new ShiftSummary(5, "Rocío Recepción", "2026-10-08T07:00:00", "2026-10-08T15:02:00", 50000,
                memberships, fridge, byMethod, 150000, 170000, 169000L, -1000L, "Faltó un billete de $10");
    }

    private static List<String> lines(Ticket t) {
        return t.lines().stream().map(Ticket.Line::text).toList();
    }

    @Test
    void ningunaLineaPasaDe32Columnas() {
        String longName = "María Guadalupe de los Ángeles Hernández Ñúñez Castañeda";
        for (Ticket t : List.of(TicketRenderer.payment(payment(longName, "Mensual"), ORIGINAL),
                TicketRenderer.payment(payment(longName, "Mensual"), COPIA),
                TicketRenderer.shiftClose(shift(), ORIGINAL),
                TicketRenderer.test(ORIGINAL, "2026-10-08T10:00:00", "Admin", "Puerto COM4 · ESC t 2 · IBM850 · 32 col"))) {
            assertThat(lines(t)).allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(W));
        }
    }

    @Test
    void cobro_llevaCabeceraYTodosLosDatos() {
        String text = TicketRenderer.payment(payment("Daniela Ortega", "Mensual"), ORIGINAL).toText();
        assertThat(text)
                .contains("Monster Gym", "Comprobante no fiscal", "COBRO DE MEMBRESÍA")
                .contains("Socio: 0412", "Daniela Ortega")
                .contains("08/10/2026 16:38", "Atendió: Rocío Recepción")
                .contains("08/10/2026", "07/11/2026", "Mensual", "Efectivo", "$1,350.00")
                .contains("Gracias por entrenar con", "nosotros.")  // el pie se parte a 32 columnas
                .doesNotContain("COPIA");
        assertThat(lines(TicketRenderer.payment(payment("Daniela Ortega", "Mensual"), ORIGINAL)))
                .anySatisfy(l -> assertThat(l).startsWith("Folio:").endsWith("000123").hasSize(W));
    }

    @Test
    void montos_alineadosALaDerecha() {
        List<String> l = lines(TicketRenderer.payment(payment("Daniela Ortega", "Mensual"), ORIGINAL));
        String total = l.stream().filter(s -> s.startsWith("TOTAL")).findFirst().orElseThrow();
        assertThat(total).hasSize(W).endsWith("$1,350.00");
        String plan = l.stream().filter(s -> s.startsWith("Plan:")).findFirst().orElseThrow();
        assertThat(plan).hasSize(W).endsWith("Mensual");
    }

    @Test
    void nombreLargo_sePartePorPalabrasSinPerderse() {
        String longName = "María Guadalupe de los Ángeles Hernández Ñúñez Castañeda";
        List<String> l = lines(TicketRenderer.payment(payment(longName, "Mensual"), ORIGINAL));
        int start = l.indexOf("María Guadalupe de los Ángeles");
        assertThat(start).isPositive();
        assertThat(l.get(start + 1)).isEqualTo("Hernández Ñúñez Castañeda");
    }

    @Test
    void productoLargo_seTruncaYElImporteSigueALaDerecha() {
        List<String> l = lines(TicketRenderer.shiftClose(shift(), ORIGINAL));
        String line = l.stream().filter(s -> s.startsWith("5 x Bebida")).findFirst().orElseThrow();
        assertThat(line).hasSize(W).endsWith(" $200.00");
        assertThat(line).isEqualTo("5 x Bebida isotónica sab $200.00");  // recortado, sin pisar el monto
    }

    @Test
    void copia_vaMarcadaArribaYAbajoConQuienLaPidio() {
        Ticket t = TicketRenderer.payment(payment("Daniela Ortega", "Mensual"), COPIA);
        assertThat(t.lines().stream().filter(l -> l.text().contains("*** COPIA ***")).count()).isEqualTo(2);
        assertThat(t.lines()).anySatisfy(l -> {
            assertThat(l.text()).contains("*** COPIA ***");
            assertThat(l.tall()).isTrue();
        });
        assertThat(t.toText()).contains("Reimpreso:", "09/10/2026 10:15", "Por: Admin Prueba");
    }

    @Test
    void cobroCancelado_loIndica() {
        var p = payment("Daniela Ortega", "Mensual");
        var voided = new PaymentReceipt(p.id(), p.folio(), p.member(), p.plan(), p.startsOn(), p.endsOn(),
                p.amountCents(), p.method(), p.employeeName(), p.paidAt(), p.shiftId(), "2026-10-08T17:00:00",
                "Plan equivocado");
        assertThat(TicketRenderer.payment(voided, COPIA).toText()).contains("PAGO CANCELADO", "Motivo: Plan equivocado");
    }

    @Test
    void cierre_llevaPlanesProductosMetodosYEfectivo() {
        List<String> l = lines(TicketRenderer.shiftClose(shift(), ORIGINAL));
        String text = String.join("\n", l);
        assertThat(text).contains("CIERRE DE TURNO", "Atendió: Rocío Recepción", "Folio:")
                .contains("08/10/2026 07:00", "08/10/2026 15:02")
                .contains("Faltó un billete de $10");
        assertThat(l).contains(
                "3 x Mensual              $900.00",
                "1 x Quincenal            $180.00",
                "Membresías: 4          $1,200.00",
                "2 x Agua 600 ml          $100.00",
                "Piezas: 7                $300.00",
                "Efectivo               $1,200.00",
                "Tarjeta                  $300.00",
                "Transferencia              $0.00",
                "TOTAL                  $1,500.00",
                "Fondo inicial            $500.00",
                "Esperado               $1,700.00",
                "Contado                $1,690.00",
                "Diferencia               -$10.00");
    }

    @Test
    void cierreSinMovimientos_loDice() {
        Map<String, Long> zero = new LinkedHashMap<>(Map.of("efectivo", 0L, "tarjeta", 0L, "transferencia", 0L));
        var s = new ShiftSummary(6, "Ana", "2026-10-08T07:00:00", "2026-10-08T08:00:00", 50000,
                new Memberships(0, 0, zero, List.of()), new Fridge(0, 0, 0, zero, List.of()), zero, 0, 50000,
                50000L, 0L, null);
        assertThat(TicketRenderer.shiftClose(s, ORIGINAL).toText())
                .contains("Sin cobros de membresía", "Sin ventas de refri")
                .doesNotContain("OBSERVACIONES");
    }

    @Test
    void prueba_traeLaLineaDeAcentosYLaRegla() {
        String text = TicketRenderer.test(ORIGINAL, "2026-10-08T10:00:00", "Admin", "Puerto COM4 · ESC t 2").toText();
        assertThat(text).contains("áéíóúñÑü¿¡$", "12345678901234567890123456789012", "ESC t 2");
    }

    @Test
    void formatosDeDinero() {
        assertThat(TicketRenderer.money(0)).isEqualTo("$0.00");
        assertThat(TicketRenderer.money(5)).isEqualTo("$0.05");
        assertThat(TicketRenderer.money(300000)).isEqualTo("$3,000.00");
        assertThat(TicketRenderer.money(123456789)).isEqualTo("$1,234,567.89");
        assertThat(TicketRenderer.money(-1050)).isEqualTo("-$10.50");
        assertThat(TicketRenderer.signedMoney(1000)).isEqualTo("+$10.00");
        assertThat(TicketRenderer.signedMoney(0)).isEqualTo("$0.00");
    }
}

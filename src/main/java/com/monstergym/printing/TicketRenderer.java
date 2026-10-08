package com.monstergym.printing;

import com.monstergym.payment.PaymentReceipt;
import com.monstergym.shift.ShiftSummary;
import com.monstergym.shift.ShiftSummary.PlanLine;
import com.monstergym.shift.ShiftSummary.ProductLine;

import java.util.Locale;
import java.util.Map;

/**
 * Contenido de los tickets (docs/reglas-negocio.md, sección Tickets).
 * Cabecera: gimnasio, "Comprobante no fiscal", folio, fecha y hora, empleado. Pie: setting.ticket_footer.
 */
public final class TicketRenderer {

    /** Línea para verificar en papel la página de códigos. */
    public static final String ACCENT_SAMPLE = "áéíóúñÑü¿¡$";

    private static final Map<String, String> METHOD_LABEL = Map.of(
            "efectivo", "Efectivo", "tarjeta", "Tarjeta", "transferencia", "Transferencia");

    /** Datos comunes. copy es null en el ticket original. */
    public record Context(String gymName, String footer, int width, Copy copy) {
    }

    /** Reimpresión: cuándo y quién la pidió. */
    public record Copy(String requestedAt, String requestedBy) {
    }

    private TicketRenderer() {
    }

    public static Ticket payment(PaymentReceipt p, Context ctx) {
        TicketBuilder t = header(ctx, "COBRO DE MEMBRESÍA", p.folio(), p.paidAt(), p.employeeName());
        t.text("Socio: " + p.member().number())
                .wrap(p.member().fullName())
                .leftRight("Plan:", p.plan().name())
                .leftRight("Del:", day(p.startsOn()))
                .leftRight("Al:", day(p.endsOn()))
                .leftRight("Método:", METHOD_LABEL.getOrDefault(p.method(), p.method()))
                .separator()
                .total("TOTAL", money(p.amountCents()));
        if (p.voidedAt() != null) {
            t.blank().title("PAGO CANCELADO").text("Cancelado: " + dateTime(p.voidedAt()));
            if (p.voidReason() != null) t.wrap("Motivo: " + p.voidReason());
        }
        return footer(t, ctx);
    }

    public static Ticket shiftClose(ShiftSummary s, Context ctx) {
        TicketBuilder t = header(ctx, "CIERRE DE TURNO", folio(s.id()), s.closedAt(), s.employeeName());
        t.leftRight("Apertura:", dateTime(s.openedAt()))
                .leftRight("Cierre:", s.closedAt() == null ? "Turno abierto" : dateTime(s.closedAt()));

        t.section("MEMBRESÍAS");
        if (s.memberships().byPlan().isEmpty()) t.text("Sin cobros de membresía");
        for (PlanLine l : s.memberships().byPlan()) {
            t.leftRight(l.count() + " x " + l.plan(), money(l.totalCents()));
        }
        t.bold(TicketBuilder.leftRight("Membresías: " + s.memberships().count(),
                money(s.memberships().totalCents()), t.width()));

        t.section("REFRI");
        if (s.fridge().byProduct().isEmpty()) t.text("Sin ventas de refri");
        for (ProductLine l : s.fridge().byProduct()) {
            t.leftRight(l.qty() + " x " + l.product(), money(l.totalCents()));
        }
        t.bold(TicketBuilder.leftRight("Piezas: " + s.fridge().units(), money(s.fridge().totalCents()), t.width()));

        t.section("POR MÉTODO DE PAGO");
        s.byMethod().forEach((method, cents) -> t.leftRight(METHOD_LABEL.getOrDefault(method, method), money(cents)));
        t.total("TOTAL", money(s.totalCents()));

        t.section("EFECTIVO EN CAJA");
        t.leftRight("Fondo inicial", money(s.openingCashCents()))
                .leftRight("Esperado", money(s.expectedCashCents()))
                .leftRight("Contado", s.countedCashCents() == null ? "-" : money(s.countedCashCents()))
                .bold(TicketBuilder.leftRight("Diferencia",
                        s.differenceCents() == null ? "-" : signedMoney(s.differenceCents()), t.width()));
        if (s.notes() != null && !s.notes().isBlank()) {
            t.section("OBSERVACIONES").wrap(s.notes());
        }
        return footer(t, ctx);
    }

    /** Ticket de prueba de Admin: acentos, regla de columnas y configuración usada. */
    public static Ticket test(Context ctx, String now, String employeeName, String configLine) {
        TicketBuilder t = header(ctx, "PRUEBA DE IMPRESORA", null, now, employeeName);
        String ruler = "1234567890".repeat(t.width() / 10 + 1).substring(0, t.width());
        t.text("Acentos y signos:")
                .center(ACCENT_SAMPLE)
                .text("Si ves ? o letras raras, cambia")
                .text("la página de códigos en Admin.")
                .separator()
                .text(ruler)
                .wrap(configLine);
        return footer(t, ctx);
    }

    private static TicketBuilder header(Context ctx, String kind, String folio, String at, String employee) {
        TicketBuilder t = new TicketBuilder(ctx.width());
        t.title(ctx.gymName()).center("Comprobante no fiscal").separator();
        if (ctx.copy() != null) {
            t.title("*** COPIA ***");
        }
        t.bold(kind);
        if (folio != null) t.leftRight("Folio:", folio);
        if (at != null) t.leftRight("Fecha:", dateTime(at));
        t.text("Atendió: " + employee);
        if (ctx.copy() != null) {
            t.leftRight("Reimpreso:", dateTime(ctx.copy().requestedAt()))
                    .text("Por: " + ctx.copy().requestedBy());
        }
        return t.separator();
    }

    private static Ticket footer(TicketBuilder t, Context ctx) {
        t.separator();
        if (ctx.copy() != null) t.center("*** COPIA ***");
        if (ctx.footer() != null && !ctx.footer().isBlank()) {
            TicketBuilder.wrap(ctx.footer(), t.width()).forEach(t::center);
        }
        return t.build();
    }

    // ---------- Formatos ----------

    public static String folio(long id) {
        return String.format("%06d", id);
    }

    /** 135000 → "$1,350.00" */
    public static String money(long cents) {
        String sign = cents < 0 ? "-" : "";
        long abs = Math.abs(cents);
        return sign + "$" + String.format(Locale.ROOT, "%,d.%02d", abs / 100, abs % 100);
    }

    /** Diferencia con signo: "+$10.00", "-$5.00" o "$0.00". */
    public static String signedMoney(long cents) {
        return (cents > 0 ? "+" : "") + money(cents);
    }

    /** "2026-10-08T16:38:12" → "08/10/2026 16:38" */
    static String dateTime(String iso) {
        if (iso == null || iso.length() < 16) return iso == null ? "" : iso;
        return day(iso.substring(0, 10)) + " " + iso.substring(11, 16);
    }

    /** "2026-10-08" → "08/10/2026" */
    static String day(String iso) {
        if (iso == null || iso.length() < 10) return iso == null ? "" : iso;
        return iso.substring(8, 10) + "/" + iso.substring(5, 7) + "/" + iso.substring(0, 4);
    }
}

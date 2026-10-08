package com.monstergym.printing;

import com.monstergym.auth.Employee;
import com.monstergym.auth.EmployeeRepository;
import com.monstergym.common.Times;
import com.monstergym.payment.PaymentRepository;
import com.monstergym.shift.ShiftService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** Arma el ticket de un trabajo con los datos actuales de la base (el contenido de un cobro o corte no cambia). */
@Component
class JobTickets {

    private final PaymentRepository payments;
    private final ShiftService shifts;
    private final EmployeeRepository employees;
    private final Clock clock;

    JobTickets(PaymentRepository payments, ShiftService shifts, EmployeeRepository employees, Clock clock) {
        this.payments = payments;
        this.shifts = shifts;
        this.employees = employees;
        this.clock = clock;
    }

    /** Lanza PrinterException no reintentable si lo que se iba a imprimir ya no existe. */
    Ticket render(PrintJob job, PrinterConfig config) throws PrinterException {
        String employee = employees.findById(job.employeeId()).map(Employee::name).orElse("-");
        TicketRenderer.Copy copy = job.isCopy() ? new TicketRenderer.Copy(job.createdAt(), employee) : null;
        var ctx = new TicketRenderer.Context(config.gymName(), config.footer(), config.width(), copy);
        return switch (job.kind()) {
            case "cobro" -> TicketRenderer.payment(payments.findReceipt(ref(job))
                    .orElseThrow(() -> new PrinterException("No se encontró el pago a imprimir.", false)), ctx);
            case "cierre_turno" -> TicketRenderer.shiftClose(shifts.summaryOf(ref(job))
                    .orElseThrow(() -> new PrinterException("No se encontró el turno a imprimir.", false)), ctx);
            case "prueba" -> TicketRenderer.test(ctx, Times.format(LocalDateTime.now(clock)), employee,
                    config.describe());
            default -> throw new PrinterException("Tipo de ticket desconocido: " + job.kind(), false);
        };
    }

    private static long ref(PrintJob job) throws PrinterException {
        if (job.refId() == null) throw new PrinterException("El trabajo de impresión no tiene referencia.", false);
        return job.refId();
    }
}

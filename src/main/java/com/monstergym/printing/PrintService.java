package com.monstergym.printing;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.common.Times;
import com.monstergym.payment.PaymentRepository;
import com.monstergym.setting.SettingRepository;
import com.monstergym.shift.Shift;
import com.monstergym.shift.ShiftRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Encola tickets en print_job. Las llamadas automáticas (cobro y cierre) nunca lanzan excepción:
 * una falla al encolar se devuelve como PrintInfo "fallido" y el cobro o cierre ya quedó guardado.
 */
@Service
public class PrintService {

    private static final Logger log = LoggerFactory.getLogger(PrintService.class);

    public record PrinterStatus(boolean enabled, String mode, String transport, String printerName, String port,
                                int codepage, String charset, Boolean ready, String detail, int pending,
                                int failedToday, PrintJob lastJob, List<String> availablePorts,
                                List<String> availablePrinters) {
    }

    private final PrintJobRepository jobs;
    private final PaymentRepository payments;
    private final ShiftRepository shifts;
    private final SettingRepository settings;
    private final PrintWorker worker;
    private final TicketPrinter printer;
    private final Clock clock;

    public PrintService(PrintJobRepository jobs, PaymentRepository payments, ShiftRepository shifts,
                        SettingRepository settings, PrintWorker worker, TicketPrinter printer, Clock clock) {
        this.jobs = jobs;
        this.payments = payments;
        this.shifts = shifts;
        this.settings = settings;
        this.worker = worker;
        this.printer = printer;
        this.clock = clock;
    }

    /** Ticket automático del cobro (llamar después de que el cobro se guardó). */
    public PrintInfo autoPayment(long paymentId, EmployeePrincipal employee) {
        return enqueue("cobro", paymentId, false, employee);
    }

    /** Ticket automático del cierre (llamar después de que el cierre se guardó). */
    public PrintInfo autoShiftClose(long shiftId, EmployeePrincipal employee) {
        return enqueue("cierre_turno", shiftId, false, employee);
    }

    public PrintInfo reprintPayment(long paymentId, EmployeePrincipal employee) {
        if (payments.findReceipt(paymentId).isEmpty()) throw ApiException.notFound("No se encontró el pago.");
        return requireEnqueued(enqueue("cobro", paymentId, true, employee));
    }

    public PrintInfo reprintShift(long shiftId, EmployeePrincipal employee) {
        Shift shift = shifts.findById(shiftId).orElseThrow(() -> ApiException.notFound("No se encontró el turno."));
        if (shift.isOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "TURNO_ABIERTO",
                    "El turno sigue abierto: su ticket de cierre se imprime al cerrarlo.");
        }
        return requireEnqueued(enqueue("cierre_turno", shiftId, true, employee));
    }

    public PrintInfo test(EmployeePrincipal employee) {
        return requireEnqueued(enqueue("prueba", null, false, employee));
    }

    public PrintJob job(long id) {
        return jobs.findById(id).orElseThrow(() -> ApiException.notFound("No se encontró el trabajo de impresión."));
    }

    public PrinterStatus status() {
        PrinterConfig config = PrinterConfig.load(settings);
        PrintJob last = jobs.lastFinished().orElse(null);
        Boolean ready;
        String detail;
        if (!config.enabled()) {
            ready = false;
            detail = "La impresión de tickets está desactivada.";
        } else if (!PrinterConfig.isSupported(config.charsetName())) {
            ready = false;
            detail = "El charset \"" + config.charsetName() + "\" no existe en Java. Prueba IBM850 o windows-1252.";
        } else {
            TicketPrinter.Probe probe = probeSafely(config);
            if (Boolean.FALSE.equals(probe.ready()) || last == null) {
                ready = probe.ready();
                detail = probe.detail();
            } else if (PrintJob.FALLIDO.equals(last.status())) {
                ready = false;
                detail = "El último ticket falló: " + last.error();
            } else if ("entregado".equals(last.status())) {
                ready = true;
                detail = "El último ticket llegó a la impresora el " + TicketRenderer.dateTime(last.finishedAt())
                        + ". Que haya salido en papel no se puede confirmar.";
            } else {
                ready = probe.ready();
                detail = probe.detail();
            }
        }
        String since = LocalDate.now(clock) + "T00:00:00";
        return new PrinterStatus(config.enabled(), printer.mode(), printer.transport(config), config.printerName(),
                config.port(), config.codepage(), config.charsetName(), ready, detail, jobs.countUnfinished(),
                jobs.countFailedSince(since), last, availablePorts(), availablePrinters());
    }

    private PrintInfo enqueue(String kind, Long refId, boolean copy, EmployeePrincipal employee) {
        try {
            if (!settings.getBool("print_enabled", true)) return PrintInfo.disabled();
            long id = jobs.insert(kind, refId, copy, employee.id(), Times.format(LocalDateTime.now(clock)));
            worker.wake();
            return PrintInfo.queued(id);
        } catch (RuntimeException e) {
            log.error("No se pudo encolar el ticket {} {}", kind, refId, e);
            return PrintInfo.failed("No se pudo mandar el ticket a la cola de impresión.");
        }
    }

    /** Una reimpresión o prueba con la impresión desactivada sí es un error que se muestra. */
    private static PrintInfo requireEnqueued(PrintInfo info) {
        if ("desactivada".equals(info.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "IMPRESION_DESACTIVADA",
                    "La impresión de tickets está desactivada en Admin > Configuración.");
        }
        return info;
    }

    private TicketPrinter.Probe probeSafely(PrinterConfig config) {
        try {
            return printer.probe(config);
        } catch (RuntimeException | LinkageError e) {
            return new TicketPrinter.Probe(null, "No se pudo consultar la impresora: " + e.getMessage());
        }
    }

    private static List<String> availablePorts() {
        try {
            return List.of(SerialTransport.availablePorts());
        } catch (RuntimeException | LinkageError e) {
            return List.of();  // sin la librería nativa del puerto serie
        }
    }

    private static List<String> availablePrinters() {
        try {
            return List.of(WindowsSpoolerTransport.availablePrinters());
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}

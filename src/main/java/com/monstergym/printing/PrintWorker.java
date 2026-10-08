package com.monstergym.printing;

import com.monstergym.common.Times;
import com.monstergym.setting.SettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Un solo hilo que imprime los print_job pendientes en orden. El cobro y el cierre solo insertan el trabajo
 * y despiertan al worker: nunca esperan a la impresora.
 * Cada intento corre en otro hilo con timeout; si se vence, no se abre otra conexión hasta que el intento
 * colgado termine (la PT-210 solo acepta una conexión a la vez). Los reintentos esperan app.printer.retry-delays-ms.
 */
@Component
public class PrintWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(PrintWorker.class);
    /** Si nadie lo despierta, revisa la tabla cada tanto (respaldo). */
    private static final long IDLE_POLL_MS = 30_000;

    private final PrintJobRepository jobs;
    private final SettingRepository settings;
    private final JobTickets tickets;
    private final TicketPrinter printer;
    private final Clock clock;
    private final long[] retryDelaysMs;

    private final Object signal = new Object();
    private boolean wakeRequested;
    private volatile boolean running;
    private Thread thread;
    private ExecutorService attempts;
    private Future<?> stuckAttempt;

    public PrintWorker(PrintJobRepository jobs, SettingRepository settings, JobTickets tickets, TicketPrinter printer,
                       Clock clock, @Value("${app.printer.retry-delays-ms:5000,15000,30000}") String retryDelays) {
        this.jobs = jobs;
        this.settings = settings;
        this.tickets = tickets;
        this.printer = printer;
        this.clock = clock;
        this.retryDelaysMs = Arrays.stream(retryDelays.split(",")).map(String::trim).mapToLong(Long::parseLong).toArray();
    }

    /** Avisa que hay un trabajo nuevo. */
    public void wake() {
        synchronized (signal) {
            wakeRequested = true;
            signal.notifyAll();
        }
    }

    @Override
    public void start() {
        int stale = jobs.failUnfinished("La aplicación se cerró antes de imprimir. Reimprime si hace falta.", now());
        if (stale > 0) log.warn("{} trabajo(s) de impresión sin terminar se marcaron como fallidos", stale);
        attempts = Executors.newSingleThreadExecutor(r -> daemon(r, "print-attempt"));
        running = true;
        thread = daemon(this::loop, "print-worker");
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        if (attempts != null) attempts.shutdownNow();
        try {
            if (thread != null) thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        printer.release();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                var next = jobs.nextPending();
                if (next.isPresent()) {
                    process(next.get());
                    continue;
                }
                if (stuckAttempt == null || stuckAttempt.isDone()) printer.release();  // cola vacía: soltar la impresora
                synchronized (signal) {
                    if (!wakeRequested) signal.wait(IDLE_POLL_MS);
                    wakeRequested = false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // p. ej. la base ocupada: se espera un poco y se sigue, el worker nunca muere
                log.error("Error en la cola de impresión", e);
                if (!sleep(1000)) return;
            }
        }
    }

    void process(PrintJob job) {
        PrinterConfig config = PrinterConfig.load(settings);
        String transport = printer.transport(config);
        if (!config.enabled()) {
            jobs.markSending(job.id(), job.attempts(), transport);
            jobs.finish(job.id(), PrintJob.FALLIDO, "La impresión de tickets está desactivada.", now());
            return;
        }
        Ticket ticket;
        try {
            ticket = tickets.render(job, config);
        } catch (PrinterException e) {
            jobs.finish(job.id(), PrintJob.FALLIDO, e.getMessage(), now());
            return;
        } catch (RuntimeException e) {
            log.error("No se pudo armar el ticket {}", job.id(), e);
            jobs.finish(job.id(), PrintJob.FALLIDO, "No se pudo armar el ticket: " + e.getMessage(), now());
            return;
        }
        for (int attempt = 1; running; attempt++) {
            jobs.markSending(job.id(), attempt, transport);
            try {
                TicketPrinter.Delivery delivery = attempt(job, ticket, config);
                jobs.finish(job.id(), delivery.status, null, now());
                return;
            } catch (PrinterException e) {
                log.warn("Ticket {} ({}), intento {}: {}", job.id(), job.kind(), attempt, e.getMessage());
                if (!e.retryable() || attempt > config.retries()) {
                    jobs.finish(job.id(), PrintJob.FALLIDO, e.getMessage(), now());
                    return;
                }
                jobs.recordError(job.id(), e.getMessage() + " Reintentando…");
                if (!sleep(retryDelaysMs[Math.min(attempt - 1, retryDelaysMs.length - 1)])) return;
            }
        }
    }

    private TicketPrinter.Delivery attempt(PrintJob job, Ticket ticket, PrinterConfig config) throws PrinterException {
        long timeoutMs = config.timeoutSeconds() * 1000L;
        if (stuckAttempt != null && !stuckAttempt.isDone()) {
            // Un intento anterior sigue colgado abriendo la conexión: no se abre otra encima
            try {
                stuckAttempt.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new PrinterException("La impresora sigue ocupada con el intento anterior.", true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PrinterException("Se detuvo la cola de impresión.", false);
            } catch (ExecutionException | java.util.concurrent.CancellationException ignored) {
                // terminó (bien o mal): ya se puede intentar de nuevo
            }
            stuckAttempt = null;
        }
        Future<TicketPrinter.Delivery> future = attempts.submit(
                () -> printer.print(job.id(), job.kind(), ticket, config));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            stuckAttempt = future;
            throw new PrinterException("La impresora no respondió en " + config.timeoutSeconds()
                    + " s (apagada, sin batería o fuera de alcance).", true);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PrinterException pe) throw pe;
            throw new PrinterException("Error inesperado al imprimir: " + e.getCause().getMessage(), true, e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new PrinterException("Se detuvo la cola de impresión.", false);
        }
    }

    private String now() {
        return Times.format(LocalDateTime.now(clock));
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}

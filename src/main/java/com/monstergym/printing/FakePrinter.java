package com.monstergym.printing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Impresora de desarrollo: guarda cada ticket en tickets-dev/ como texto (.txt) y con los bytes ESC/POS
 * que recibiría la impresora (.bin), para revisar formato y página de códigos sin hardware.
 * Puede simular fallas (app.printer.fake-fail=true, o failNext desde las pruebas) y lentitud.
 */
public class FakePrinter implements TicketPrinter {

    private final Path dir;
    private final boolean alwaysFail;
    private final AtomicInteger failures = new AtomicInteger();
    private volatile long delayMs;

    public FakePrinter(Path dir, boolean alwaysFail) {
        this.dir = dir;
        this.alwaysFail = alwaysFail;
    }

    /** Las siguientes n impresiones fallan (pruebas de reintentos). */
    public void failNext(int n) {
        failures.set(n);
    }

    /** Tarda ms en cada impresión (pruebas de timeout). */
    public void setDelayMs(long ms) {
        delayMs = ms;
    }

    public Path dir() {
        return dir;
    }

    @Override
    public Delivery print(long jobId, String kind, Ticket ticket, PrinterConfig config) throws PrinterException {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PrinterException("Se interrumpió la impresión.", true, e);
            }
        }
        if (alwaysFail || failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new PrinterException("Impresora simulada apagada (modo desarrollo).", true);
        }
        String name = String.format("%06d-%s", jobId, kind);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name + ".txt"), ticket.toText(), StandardCharsets.UTF_8);
            Files.write(dir.resolve(name + ".bin"), EscPosEncoder.encode(ticket, config.codepage(), config.charset()));
        } catch (IOException e) {
            throw new PrinterException("No se pudo guardar el ticket en " + dir.toAbsolutePath() + ".", true, e);
        }
        return Delivery.ENTREGADO;
    }

    @Override
    public Probe probe(PrinterConfig config) {
        return alwaysFail
                ? new Probe(false, "Modo desarrollo con fallas simuladas (app.printer.fake-fail).")
                : new Probe(true, "Modo desarrollo: los tickets se guardan en " + dir.toAbsolutePath().normalize() + ".");
    }

    @Override
    public String transport(PrinterConfig config) {
        return "archivo";
    }

    @Override
    public String mode() {
        return "fake";
    }
}

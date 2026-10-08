package com.monstergym.printing;

/**
 * Salida de los tickets. EscPosPrinter manda ESC/POS a la PT-210; FakePrinter los guarda en tickets-dev/.
 * Solo la usa el PrintWorker (un hilo), así que las implementaciones no necesitan ser thread-safe en print.
 */
public interface TicketPrinter {

    /** Hasta dónde se pudo confirmar la entrega. Ninguno significa "salió en papel": eso no se puede saber. */
    enum Delivery {
        /** La impresora recibió los bytes (puerto COM abierto) o el archivo de desarrollo quedó escrito. */
        ENTREGADO("entregado"),
        /** La cola de Windows lo aceptó; no confirma que la impresora esté encendida. */
        EN_COLA_WINDOWS("en_cola_windows");

        public final String status;

        Delivery(String status) {
            this.status = status;
        }
    }

    /** Resultado pasivo del estado (sin abrir la conexión Bluetooth). ready null = no se puede saber. */
    record Probe(Boolean ready, String detail) {
    }

    /** Envía el ticket. Lanza PrinterException si falla. */
    Delivery print(long jobId, String kind, Ticket ticket, PrinterConfig config) throws PrinterException;

    /** Libera la conexión cuando la cola se vacía (la impresora solo admite una a la vez). */
    default void release() {
    }

    /** Estado sin imprimir nada. */
    Probe probe(PrinterConfig config);

    /** serial, windows o archivo: se guarda en print_job.transport. */
    String transport(PrinterConfig config);

    /** fake o real, para mostrarlo en Admin. */
    String mode();
}

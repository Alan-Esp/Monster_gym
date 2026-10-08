package com.monstergym.printing;

/** Cómo llegan los bytes ESC/POS a la impresora (setting.printer_transport). */
interface PrinterTransport {

    TicketPrinter.Delivery send(byte[] data, PrinterConfig config) throws PrinterException;

    default void release() {
    }

    TicketPrinter.Probe probe(PrinterConfig config);
}

package com.monstergym.printing;

/** Impresora real: ESC/POS por puerto COM (serial) o por la cola de Windows, según setting.printer_transport. */
public class EscPosPrinter implements TicketPrinter {

    private final PrinterTransport serial;
    private final PrinterTransport windows;

    public EscPosPrinter() {
        this(new SerialTransport(), new WindowsSpoolerTransport());
    }

    EscPosPrinter(PrinterTransport serial, PrinterTransport windows) {
        this.serial = serial;
        this.windows = windows;
    }

    @Override
    public Delivery print(long jobId, String kind, Ticket ticket, PrinterConfig config) throws PrinterException {
        if (!PrinterConfig.isSupported(config.charsetName())) {
            throw new PrinterException("El charset \"" + config.charsetName() + "\" no existe en Java.", false);
        }
        byte[] data = EscPosEncoder.encode(ticket, config.codepage(), config.charset());
        PrinterTransport transport = transportFor(config);
        // Si se cambió de transporte, se suelta el puerto COM para no dejarlo ocupado
        if (transport == windows) serial.release();
        return transport.send(data, config);
    }

    @Override
    public void release() {
        serial.release();
        windows.release();
    }

    @Override
    public Probe probe(PrinterConfig config) {
        return transportFor(config).probe(config);
    }

    @Override
    public String transport(PrinterConfig config) {
        return config.transport();
    }

    @Override
    public String mode() {
        return "real";
    }

    private PrinterTransport transportFor(PrinterConfig config) {
        return PrinterConfig.WINDOWS.equals(config.transport()) ? windows : serial;
    }
}

package com.monstergym.printing;

/**
 * Impresora real: bytes ESC/POS a la impresora instalada en Windows (setting.printer_name).
 * Con la PT-210 por Bluetooth, Windows acepta el trabajo aunque esté apagada: el resultado es
 * "en_cola_windows", nunca "entregado".
 */
public class EscPosPrinter implements TicketPrinter {

    private final WindowsSpoolerTransport windows = new WindowsSpoolerTransport();

    @Override
    public Delivery print(long jobId, String kind, Ticket ticket, PrinterConfig config) throws PrinterException {
        if (!PrinterConfig.isSupported(config.charsetName())) {
            throw new PrinterException("El charset \"" + config.charsetName() + "\" no existe en Java.", false);
        }
        return windows.send(EscPosEncoder.encode(ticket, config.codepage(), config.charset()), config);
    }

    @Override
    public Probe probe(PrinterConfig config) {
        return windows.probe(config);
    }

    @Override
    public String transport(PrinterConfig config) {
        return "windows";
    }

    @Override
    public String mode() {
        return "real";
    }
}

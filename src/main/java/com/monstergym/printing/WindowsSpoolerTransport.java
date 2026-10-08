package com.monstergym.printing;

import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintException;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.standard.JobName;
import javax.print.attribute.standard.QueuedJobCount;
import java.util.Arrays;
import java.util.Optional;

/**
 * Manda los bytes crudos a la impresora instalada en Windows (setting.printer_name) con javax.print.
 * Con el controlador "Generic / Text Only" los bytes pasan sin cambios. Windows acepta el trabajo aunque
 * la impresora esté apagada: el resultado es "en_cola_windows", no "entregado".
 */
class WindowsSpoolerTransport implements PrinterTransport {

    @Override
    public TicketPrinter.Delivery send(byte[] data, PrinterConfig config) throws PrinterException {
        PrintService service = find(config.printerName()).orElseThrow(() -> new PrinterException(
                "No existe la impresora \"" + config.printerName() + "\" en Windows.", false));
        DocPrintJob job = service.createPrintJob();
        Doc doc = new SimpleDoc(data, DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
        try {
            job.print(doc, new HashPrintRequestAttributeSet(new JobName("Monster Gym ticket", null)));
        } catch (PrintException e) {
            throw new PrinterException("Windows rechazó el trabajo de impresión: " + e.getMessage(), true, e);
        }
        return TicketPrinter.Delivery.EN_COLA_WINDOWS;
    }

    @Override
    public TicketPrinter.Probe probe(PrinterConfig config) {
        Optional<PrintService> service = find(config.printerName());
        if (service.isEmpty()) {
            return new TicketPrinter.Probe(false, "No existe la impresora \"" + config.printerName() + "\" en Windows.");
        }
        QueuedJobCount queued = service.get().getAttribute(QueuedJobCount.class);
        if (queued != null && queued.getValue() > 0) {
            return new TicketPrinter.Probe(false, "Hay " + queued.getValue() + " trabajo(s) detenido(s) en la cola de "
                    + "Windows: la impresora puede estar apagada. Al encenderla podrían salir tickets atrasados.");
        }
        return new TicketPrinter.Probe(null,
                "Windows acepta trabajos, pero no puede confirmar que la impresora esté encendida.");
    }

    static Optional<PrintService> find(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .filter(s -> s.getName().equalsIgnoreCase(name.trim()))
                .findFirst();
    }

    static String[] availablePrinters() {
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(null, null))
                .map(PrintService::getName).sorted().toArray(String[]::new);
    }
}

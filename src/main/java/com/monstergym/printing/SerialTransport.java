package com.monstergym.printing;

import com.fazecast.jSerialComm.SerialPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Escribe directo al puerto COM saliente del Bluetooth SPP (setting.printer_port).
 * Abrir el puerto obliga a Windows a conectar con la impresora: si está apagada o lejos, falla aquí
 * (a diferencia de la cola de Windows, que acepta todo). La conexión se reutiliza mientras haya tickets
 * en la cola y se cierra con release(), porque la PT-210 solo acepta una conexión a la vez.
 */
class SerialTransport implements PrinterTransport {

    private static final Logger log = LoggerFactory.getLogger(SerialTransport.class);
    private static final byte[] PAPER_STATUS = {0x10, 0x04, 0x04};  // DLE EOT 4: sensor de papel
    private static final int STATUS_WAIT_MS = 1500;

    private SerialPort open;
    private String openName;

    @Override
    public synchronized TicketPrinter.Delivery send(byte[] data, PrinterConfig config) throws PrinterException {
        SerialPort port = connect(config);
        try {
            if (config.statusCheck()) checkPaper(port);
            int written = port.writeBytes(data, data.length);
            if (written != data.length) {
                throw new PrinterException("Se perdió la conexión con la impresora al enviar el ticket.", true);
            }
            waitDrained(port, config.timeoutSeconds() * 1000L);
            return TicketPrinter.Delivery.ENTREGADO;
        } catch (PrinterException e) {
            release();  // conexión dudosa: el siguiente intento abre una nueva
            throw e;
        }
    }

    private SerialPort connect(PrinterConfig config) throws PrinterException {
        if (open != null && open.isOpen() && config.port().equalsIgnoreCase(openName)) return open;
        release();
        if (config.port().isBlank()) throw new PrinterException("Falta configurar el puerto COM de la impresora.", false);
        SerialPort port;
        try {
            port = SerialPort.getCommPort(config.port());
        } catch (RuntimeException e) {
            throw new PrinterException("No existe el puerto " + config.port() + " en Windows.", false, e);
        }
        // En Bluetooth SPP la velocidad es virtual; se fija por compatibilidad con cable serie.
        port.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING | SerialPort.TIMEOUT_WRITE_BLOCKING,
                STATUS_WAIT_MS, config.timeoutSeconds() * 1000);
        if (!port.openPort()) {
            throw new PrinterException("No se pudo conectar con la impresora en " + config.port()
                    + ": está apagada, fuera de alcance u ocupada por otro equipo.", true);
        }
        open = port;
        openName = config.port();
        return port;
    }

    /** Si la impresora contesta DLE EOT 4 y reporta "sin papel", no se manda el ticket. Si no contesta, se sigue. */
    private static void checkPaper(SerialPort port) throws PrinterException {
        port.flushIOBuffers();
        if (port.writeBytes(PAPER_STATUS, PAPER_STATUS.length) != PAPER_STATUS.length) {
            throw new PrinterException("Se perdió la conexión con la impresora.", true);
        }
        byte[] answer = new byte[1];
        if (port.readBytes(answer, 1) != 1) return;           // este modelo no informa el papel
        int b = answer[0] & 0xFF;
        if ((b & 0x93) != 0x12) return;                        // no es una respuesta de estado válida
        if ((b & 0x60) != 0) throw new PrinterException("La impresora no tiene papel.", true);
    }

    private static void waitDrained(SerialPort port, long timeoutMs) throws PrinterException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (port.bytesAwaitingWrite() > 0) {
            if (System.currentTimeMillis() > until) {
                throw new PrinterException("La impresora no terminó de recibir el ticket.", true);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PrinterException("Se interrumpió el envío del ticket.", true, e);
            }
        }
    }

    @Override
    public synchronized void release() {
        if (open == null) return;
        try {
            open.closePort();
        } catch (RuntimeException e) {
            log.warn("No se pudo cerrar {}", openName, e);
        }
        open = null;
        openName = null;
    }

    @Override
    public TicketPrinter.Probe probe(PrinterConfig config) {
        boolean exists = Arrays.stream(SerialPort.getCommPorts())
                .anyMatch(p -> p.getSystemPortName().equalsIgnoreCase(config.port()));
        if (!exists) {
            return new TicketPrinter.Probe(false, "No se encontró el puerto " + config.port()
                    + " en Windows. Revisa el emparejamiento Bluetooth y el puerto COM saliente.");
        }
        return new TicketPrinter.Probe(null, "El puerto " + config.port()
                + " existe. Solo se sabe si la impresora responde al imprimir.");
    }

    static String[] availablePorts() {
        return Arrays.stream(SerialPort.getCommPorts()).map(SerialPort::getSystemPortName).sorted().toArray(String[]::new);
    }
}

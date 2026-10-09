package com.monstergym.fingerprint;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Lector simulado para desarrollo y pruebas. Cada "dedo" es un texto: touch("ana-indice") equivale a poner ese
 * dedo en el lector; touch("") es una muestra de mala calidad. La plantilla guarda el texto del dedo.
 * En el perfil dev se maneja con POST /api/fingerprint/fake/touch y /fake/connected.
 */
public class FakeReader implements FingerprintReader {

    static final int SAMPLES = 4;
    private static final String PREFIX = "FAKE1:";

    private record FakeCapture(String finger) implements Capture {
    }

    private record FakeTemplate(String finger) implements Template {
    }

    private volatile Listener listener;
    private volatile boolean connected = true;
    private volatile BlockingQueue<String> enrollQueue;
    private volatile String abortMessage;

    /** Simula poner un dedo. Sin conexión no pasa nada (como el lector real desconectado). */
    public void touch(String finger) {
        if (!connected) return;
        String f = finger == null ? "" : finger.trim();
        BlockingQueue<String> q = enrollQueue;
        if (q != null) {
            q.offer(f);
            return;
        }
        Listener l = listener;
        if (l != null && !f.isEmpty()) l.onCapture(new FakeCapture(f));
    }

    /** Simula conectar o desconectar el lector. Desconectar interrumpe un enrolamiento en curso. */
    public void setConnected(boolean value) {
        if (connected == value) return;
        connected = value;
        if (!value) abortEnroll("Se desconectó el lector de huella.");
        Listener l = listener;
        if (l != null) l.onConnectionChange(value);
    }

    public boolean enrolling() {
        return enrollQueue != null;
    }

    @Override
    public void start(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void stop() {
        listener = null;
        abortEnroll("Se detuvo el lector de huella.");
    }

    @Override
    public boolean connected() {
        return connected;
    }

    @Override
    public byte[] enroll(EnrollProgress progress, Duration timeoutPerSample) throws FingerprintException {
        if (!connected) throw new FingerprintException("El lector de huella no está conectado.");
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        synchronized (this) {
            if (enrollQueue != null) throw new FingerprintException("Ya hay una captura de huella en curso.");
            abortMessage = null;
            enrollQueue = q;
        }
        try {
            String first = null;
            int remaining = SAMPLES;
            while (remaining > 0) {
                String f = q.poll(timeoutPerSample.toMillis(), TimeUnit.MILLISECONDS);
                if (abortMessage != null) throw new FingerprintException(abortMessage);
                if (f == null) throw new FingerprintException("No se colocó el dedo a tiempo. Intenta de nuevo.");
                if (f.isEmpty()) {
                    progress.onPoorQuality("La imagen salió poco clara.");
                    continue;
                }
                if (first == null) {
                    first = f;
                } else if (!first.equals(f)) {
                    throw new FingerprintException("Las muestras no son del mismo dedo. Intenta de nuevo.");
                }
                progress.onSample(--remaining);
            }
            return (PREFIX + first).getBytes(StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FingerprintException("Se interrumpió la captura de huella.");
        } finally {
            enrollQueue = null;
        }
    }

    @Override
    public void cancelEnroll() {
        abortEnroll("Captura de huella cancelada.");
    }

    private void abortEnroll(String message) {
        BlockingQueue<String> q = enrollQueue;
        if (q == null) return;
        abortMessage = message;
        q.offer("");
    }

    @Override
    public Template load(byte[] serialized) {
        String s = new String(serialized, StandardCharsets.UTF_8);
        if (!s.startsWith(PREFIX) || s.length() == PREFIX.length()) {
            throw new IllegalArgumentException("No es una plantilla del lector simulado.");
        }
        return new FakeTemplate(s.substring(PREFIX.length()));
    }

    @Override
    public boolean matches(Capture capture, Template template) {
        return capture instanceof FakeCapture c && template instanceof FakeTemplate t && c.finger().equals(t.finger());
    }

    @Override
    public String mode() {
        return "fake";
    }

    @Override
    public String detail() {
        return connected ? "Lector simulado (modo desarrollo)." : "Lector simulado desconectado (modo desarrollo).";
    }
}

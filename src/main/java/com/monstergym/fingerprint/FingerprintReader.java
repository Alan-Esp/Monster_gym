package com.monstergym.fingerprint;

import java.time.Duration;

/**
 * Lector de huella. DigitalPersonaReader usa el U.are.U 4500 (One Touch SDK); FakeReader simula huellas en
 * desarrollo y pruebas.
 * El lector captura todo el tiempo: cada huella va al Listener para identificarla, salvo mientras dura un
 * enrolamiento, que se queda con las muestras.
 * El SDK solo compara 1 a 1; la búsqueda 1 a N la hace FingerprintService recorriendo las plantillas.
 */
public interface FingerprintReader {

    /** Huella capturada y lista para comparar. Cada implementación pone sus propios datos. */
    interface Capture {
    }

    /** Plantilla ya cargada (deserializada) para comparar rápido. */
    interface Template {
    }

    interface Listener {
        /** Llega una huella fuera de un enrolamiento. Se llama desde un hilo del lector: no debe tardar. */
        void onCapture(Capture capture);

        /** El lector se conectó o desconectó. */
        void onConnectionChange(boolean connected);
    }

    interface EnrollProgress {
        /** Muestra aceptada; remaining = cuántas faltan (0 = plantilla lista). */
        void onSample(int remaining);

        /** Muestra rechazada por calidad; hay que repetirla. */
        void onPoorQuality(String detail);
    }

    /** Arranca la captura continua. Si no hay lector, queda esperando a que se conecte. */
    void start(Listener listener);

    void stop();

    boolean connected();

    /**
     * Captura las muestras necesarias y devuelve la plantilla serializada (BLOB de member.fingerprint_template).
     * Bloquea al hilo que llama. Lanza FingerprintException si se acaba el tiempo sin dedo, se cancela o falla.
     */
    byte[] enroll(EnrollProgress progress, Duration timeoutPerSample) throws FingerprintException;

    /** Interrumpe el enrolamiento en curso, si hay. */
    void cancelEnroll();

    /** Carga una plantilla guardada. Lanza IllegalArgumentException si los bytes no son una plantilla válida. */
    Template load(byte[] serialized);

    boolean matches(Capture capture, Template template);

    /** real o fake, para mostrarlo. */
    String mode();

    /** Explicación del estado para Admin (p. ej. por qué no hay lector). */
    String detail();
}

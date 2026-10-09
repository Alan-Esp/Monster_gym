package com.monstergym.fingerprint;

/** Falla del lector o del enrolamiento. El mensaje se muestra tal cual en la interfaz. */
public class FingerprintException extends Exception {

    public FingerprintException(String message) {
        super(message);
    }

    public FingerprintException(String message, Throwable cause) {
        super(message, cause);
    }
}

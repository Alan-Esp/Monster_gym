package com.monstergym.printing;

/** Falla al imprimir, con mensaje en español para mostrar en pantalla. retryable = vale la pena reintentar. */
public class PrinterException extends Exception {

    private final boolean retryable;

    public PrinterException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public PrinterException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}

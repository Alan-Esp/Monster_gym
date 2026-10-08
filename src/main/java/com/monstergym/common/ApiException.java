package com.monstergym.common;

import org.springframework.http.HttpStatus;

/** Error de negocio que se devuelve al cliente con el formato { error: { code, message } }. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException sinTurno() {
        return new ApiException(HttpStatus.CONFLICT, "SIN_TURNO", "No tienes un turno abierto.");
    }
}

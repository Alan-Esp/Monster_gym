package com.monstergym.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** Error de negocio que se devuelve al cliente con el formato { error: { code, message, fields? } }. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fields;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getFields() {
        return fields;
    }

    public static ApiException sinTurno() {
        return new ApiException(HttpStatus.CONFLICT, "SIN_TURNO", "No tienes un turno abierto.");
    }

    public static ApiException sinTurnoParaCobrar() {
        return new ApiException(HttpStatus.CONFLICT, "SIN_TURNO", "Abre un turno antes de cobrar.");
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NO_ENCONTRADO", message);
    }

    /** Error de validación de un campo (422), con el mismo formato que las anotaciones @Valid. */
    public static ApiException invalidField(String field, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDACION",
                "Revisa los datos capturados.", Map.of(field, message));
    }
}

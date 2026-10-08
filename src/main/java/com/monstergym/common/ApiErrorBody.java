package com.monstergym.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** Cuerpo de error de la API: { "error": { "code", "message", "fields"? } }. */
public record ApiErrorBody(Detail error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(String code, String message, Map<String, String> fields) {
    }

    public static ApiErrorBody of(String code, String message) {
        return new ApiErrorBody(new Detail(code, message, null));
    }

    public static ApiErrorBody of(String code, String message, Map<String, String> fields) {
        return new ApiErrorBody(new Detail(code, message, fields));
    }

    public static final ApiErrorBody NO_AUTENTICADO =
            of("NO_AUTENTICADO", "Inicia sesión para continuar.");
    public static final ApiErrorBody SIN_PERMISO =
            of("SIN_PERMISO", "Tu rol no tiene permiso para esta acción.");
    public static final ApiErrorBody CSRF_INVALIDO =
            of("CSRF_INVALIDO", "La sesión de la página expiró. Intenta de nuevo.");
}

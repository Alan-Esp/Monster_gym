package com.monstergym.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorBody> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ApiErrorBody.of(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.unprocessableEntity()
                .body(ApiErrorBody.of("VALIDACION", "Revisa los datos capturados.", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiErrorBody.of("VALIDACION", "El formato de los datos enviados no es válido."));
    }

    /** Lo lanza @PreAuthorize dentro del controlador; se responde igual que el filtro de seguridad. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorBody> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorBody.SIN_PERMISO);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorBody> handleOther(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            // Errores propios de Spring MVC (404 de recurso, 405 de método, etc.)
            int status = er.getStatusCode().value();
            String code = status == 404 ? "NO_ENCONTRADO" : "SOLICITUD_INVALIDA";
            String message = status == 404 ? "No se encontró el recurso." : "La solicitud no es válida.";
            return ResponseEntity.status(status).body(ApiErrorBody.of(code, message));
        }
        log.error("Error no controlado", ex);
        return ResponseEntity.internalServerError()
                .body(ApiErrorBody.of("ERROR_INTERNO", "Ocurrió un error inesperado."));
    }
}

package com.monstergym.fingerprint;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.common.ApiException;
import com.monstergym.fingerprint.FingerprintService.Status;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/fingerprint")
public class FingerprintController {

    public record EnrollStarted(String enrollmentId) {
    }

    public record FakeTouch(@Size(max = 60, message = "El dedo simulado es demasiado largo.") String finger) {
    }

    public record FakeConnected(@NotNull(message = "Indica si el lector está conectado.") Boolean connected) {
    }

    private final FingerprintService service;
    private final FingerprintEvents events;
    private final FingerprintReader reader;

    public FingerprintController(FingerprintService service, FingerprintEvents events, FingerprintReader reader) {
        this.service = service;
        this.events = events;
        this.reader = reader;
    }

    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION', 'ENTRENADOR')")
    public Status status() {
        return service.status();
    }

    /** SSE: estado, identificado, no_reconocido, enrolamiento. El primer evento es el estado actual. */
    @GetMapping(path = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public SseEmitter events(@AuthenticationPrincipal EmployeePrincipal employee, HttpServletRequest request) {
        // Si el navegador corta la conexión, Tomcat vuelve a entrar aquí con un despacho de error: no hay que suscribir
        if (request.getDispatcherType() != DispatcherType.REQUEST) return null;
        return events.subscribe(employee.id(), request.getSession().getId(), "estado", service.status());
    }

    /** Enrolamiento para un socio nuevo: la plantilla queda pendiente hasta POST /api/members. */
    @PostMapping("/enroll")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public EnrollStarted enrollNew() {
        return new EnrollStarted(service.startEnroll(null));
    }

    /** Enrolamiento (o reemplazo) de la huella de un socio existente. */
    @PostMapping("/enroll/{memberId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public EnrollStarted enroll(@PathVariable long memberId) {
        return new EnrollStarted(service.startEnroll(memberId));
    }

    @PostMapping("/enroll/{enrollmentId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public void cancel(@PathVariable String enrollmentId) {
        service.cancelEnroll(enrollmentId);
    }

    // ── Solo con el lector simulado (app.fingerprint.mode=fake) ────────────────

    @PostMapping("/fake/touch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public void fakeTouch(@Valid @RequestBody FakeTouch body) {
        fake().touch(body.finger());
    }

    @PostMapping("/fake/connected")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public void fakeConnected(@Valid @RequestBody FakeConnected body) {
        fake().setConnected(body.connected());
    }

    private FakeReader fake() {
        if (reader instanceof FakeReader f) return f;
        throw ApiException.notFound("Solo existe con el lector simulado (modo desarrollo).");
    }
}

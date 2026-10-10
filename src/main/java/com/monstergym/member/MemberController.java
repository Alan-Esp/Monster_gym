package com.monstergym.member;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.member.MemberDtos.CreatedMember;
import com.monstergym.member.MemberDtos.MemberDetail;
import com.monstergym.member.MemberDtos.MemberPage;
import com.monstergym.payment.PaymentController;
import com.monstergym.payment.PaymentReceipt;
import com.monstergym.payment.PaymentWithPrint;
import com.monstergym.printing.PrintService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/members")
public class MemberController {

    private static final String PHONE_CHARS = "^[0-9 ()-]*$";
    private static final String PHONE_CHARS_MSG = "El teléfono solo puede tener números, espacios o guiones.";

    public record CreateRequest(
            @NotBlank(message = "El nombre es obligatorio.")
            @Size(max = 100, message = "El nombre es demasiado largo (máximo 100 caracteres).")
            String fullName,
            @Size(max = 20, message = "El teléfono debe tener 10 dígitos.")
            @Pattern(regexp = PHONE_CHARS, message = PHONE_CHARS_MSG)
            String phone,
            @Size(max = 500, message = "Las notas son demasiado largas (máximo 500 caracteres).")
            String notes,
            Long planId,
            @Pattern(regexp = PaymentController.METHOD_REGEX, message = PaymentController.METHOD_MESSAGE)
            String method,
            @Size(max = 64, message = "La huella capturada ya no es válida. Captúrala de nuevo.")
            String fingerprintEnrollmentId) {
    }

    public record UpdateRequest(
            @NotBlank(message = "El nombre es obligatorio.")
            @Size(max = 100, message = "El nombre es demasiado largo (máximo 100 caracteres).")
            String fullName,
            @Size(max = 20, message = "El teléfono debe tener 10 dígitos.")
            @Pattern(regexp = PHONE_CHARS, message = PHONE_CHARS_MSG)
            String phone,
            @Size(max = 500, message = "Las notas son demasiado largas (máximo 500 caracteres).")
            String notes) {
    }

    /** Alta de socio: payment es null si no se cobró un plan. */
    public record CreatedResponse(MemberDetail member, PaymentWithPrint payment) {
    }

    private final MemberService service;
    private final PrintService printing;

    public MemberController(MemberService service, PrintService printing) {
        this.service = service;
        this.printing = printing;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION', 'ENTRENADOR')")
    public MemberPage list(@RequestParam(required = false) String q,
                           @RequestParam(required = false) String status,
                           @RequestParam(defaultValue = "1") int page) {
        return service.list(q, status, page);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public CreatedResponse create(@AuthenticationPrincipal EmployeePrincipal employee,
                                  @Valid @RequestBody CreateRequest body) {
        CreatedMember created = service.create(employee, body.fullName(), body.phone(), body.notes(),
                body.planId(), body.method(), body.fingerprintEnrollmentId());
        PaymentReceipt p = created.payment();
        // Si hubo cobro, su ticket se encola después de guardar (una falla de impresión no revierte el alta)
        return new CreatedResponse(created.member(),
                p == null ? null : new PaymentWithPrint(p, printing.autoPayment(p.id(), employee)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION', 'ENTRENADOR')")
    public MemberDetail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMINISTRADOR', 'RECEPCION')")
    public MemberDetail update(@PathVariable long id, @Valid @RequestBody UpdateRequest body) {
        return service.update(id, body.fullName(), body.phone(), body.notes());
    }

    /** Baja lógica: el socio no se borra (sus pagos y visitas quedan en el historial). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMINISTRADOR')")
    public void deactivate(@PathVariable long id) {
        service.deactivate(id);
    }
}

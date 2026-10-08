package com.monstergym.employee;

import com.monstergym.auth.EmployeePrincipal;
import com.monstergym.employee.EmployeeAdminService.EmployeeItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/employees")
@PreAuthorize("hasRole('ADMINISTRADOR')")
public class EmployeeAdminController {

    private static final String USERNAME_REGEX = "^\\s*[A-Za-z0-9._-]{3,30}\\s*$";
    private static final String USERNAME_MSG = "Usuario de 3 a 30 caracteres: letras, números, punto, guion o guion bajo.";
    private static final String ROLE_REGEX = "administrador|recepcion|entrenador";
    private static final String ROLE_MSG = "Rol no válido: administrador, recepcion o entrenador.";
    private static final String PIN_MSG = "El PIN o contraseña debe tener entre 4 y 72 caracteres.";

    public record CreateRequest(
            @NotBlank(message = "Escribe el nombre.") @Size(max = 60, message = "Nombre demasiado largo.") String name,
            @NotNull(message = USERNAME_MSG) @Pattern(regexp = USERNAME_REGEX, message = USERNAME_MSG) String username,
            @NotNull(message = ROLE_MSG) @Pattern(regexp = ROLE_REGEX, message = ROLE_MSG) String role,
            @Size(max = 30, message = "Turno demasiado largo.") String shiftLabel,
            @NotNull(message = PIN_MSG) @Size(min = 4, max = 72, message = PIN_MSG) String pin) {
    }

    public record UpdateRequest(
            @NotBlank(message = "Escribe el nombre.") @Size(max = 60, message = "Nombre demasiado largo.") String name,
            @NotNull(message = USERNAME_MSG) @Pattern(regexp = USERNAME_REGEX, message = USERNAME_MSG) String username,
            @NotNull(message = ROLE_MSG) @Pattern(regexp = ROLE_REGEX, message = ROLE_MSG) String role,
            @Size(max = 30, message = "Turno demasiado largo.") String shiftLabel,
            @NotNull(message = "Indica si está activo.") Boolean active) {
    }

    public record PinRequest(@NotNull(message = PIN_MSG) @Size(min = 4, max = 72, message = PIN_MSG) String pin) {
    }

    private final EmployeeAdminService service;

    public EmployeeAdminController(EmployeeAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<EmployeeItem> list() {
        return service.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeItem create(@Valid @RequestBody CreateRequest b) {
        return service.create(b.name(), b.username(), b.role(), b.shiftLabel(), b.pin());
    }

    @PutMapping("/{id}")
    public EmployeeItem update(@AuthenticationPrincipal EmployeePrincipal actor, @PathVariable long id,
                               @Valid @RequestBody UpdateRequest b) {
        return service.update(actor, id, b.name(), b.username(), b.role(), b.shiftLabel(), b.active());
    }

    @PutMapping("/{id}/pin")
    public EmployeeItem changePin(@PathVariable long id, @Valid @RequestBody PinRequest b) {
        return service.changePin(id, b.pin());
    }
}

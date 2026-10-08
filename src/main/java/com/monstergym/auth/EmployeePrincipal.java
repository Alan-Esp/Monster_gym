package com.monstergym.auth;

import java.io.Serializable;

/** Empleado autenticado que se guarda en la sesión. No lleva el hash del PIN. */
public record EmployeePrincipal(long id, String name, String username, Role role) implements Serializable {

    public static EmployeePrincipal of(Employee e) {
        return new EmployeePrincipal(e.id(), e.name(), e.username(), e.role());
    }
}

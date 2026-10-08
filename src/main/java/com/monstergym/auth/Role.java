package com.monstergym.auth;

/**
 * Roles del sistema. El valor de la base es el código en minúsculas;
 * la autoridad de Spring es ROLE_ + nombre (para @PreAuthorize("hasRole('ADMINISTRADOR')")).
 */
public enum Role {
    ADMINISTRADOR("administrador"),
    RECEPCION("recepcion"),
    ENTRENADOR("entrenador");

    private final String dbValue;

    Role(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public String authority() {
        return "ROLE_" + name();
    }

    public static Role fromDb(String value) {
        for (Role r : values()) {
            if (r.dbValue.equals(value)) return r;
        }
        throw new IllegalArgumentException("Rol desconocido: " + value);
    }
}

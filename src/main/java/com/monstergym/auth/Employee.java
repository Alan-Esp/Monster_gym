package com.monstergym.auth;

public record Employee(
        long id,
        String name,
        String username,
        Role role,
        String shiftLabel,
        String pinHash,
        int failedAttempts,
        String lockedUntil,
        boolean active) {
}

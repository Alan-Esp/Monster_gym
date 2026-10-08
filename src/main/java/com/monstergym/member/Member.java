package com.monstergym.member;

public record Member(
        long id,
        String number,
        String fullName,
        String phone,
        String notes,
        String joinedOn,
        boolean active) {
}

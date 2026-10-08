package com.monstergym.plan;

public record Plan(long id, String name, int days, long priceCents, boolean active) {
}

package com.monstergym.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Fecha y hora local de México en el formato de la base: YYYY-MM-DDTHH:MM:SS. */
public final class Times {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private Times() {
    }

    public static String format(LocalDateTime dateTime) {
        return dateTime.format(FORMAT);
    }

    public static LocalDateTime parse(String text) {
        return text == null ? null : LocalDateTime.parse(text);
    }
}

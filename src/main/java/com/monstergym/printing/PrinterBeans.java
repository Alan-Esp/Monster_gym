package com.monstergym.printing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** app.printer.mode: real (PT-210) por defecto; fake en el perfil dev (tickets en tickets-dev/). */
@Configuration
public class PrinterBeans {

    @Bean
    public TicketPrinter ticketPrinter(@Value("${app.printer.mode:real}") String mode,
                                       @Value("${app.printer.fake-dir:tickets-dev}") String fakeDir,
                                       @Value("${app.printer.fake-fail:false}") boolean fakeFail) {
        return "fake".equalsIgnoreCase(mode.trim()) ? new FakePrinter(Path.of(fakeDir), fakeFail) : new EscPosPrinter();
    }
}

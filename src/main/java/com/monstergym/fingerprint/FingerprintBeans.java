package com.monstergym.fingerprint;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** app.fingerprint.mode: real (U.are.U 4500) por defecto; fake en el perfil dev y en pruebas. */
@Configuration
public class FingerprintBeans {

    @Bean
    public FingerprintReader fingerprintReader(@Value("${app.fingerprint.mode:real}") String mode) {
        return "fake".equalsIgnoreCase(mode.trim()) ? new FakeReader() : new DigitalPersonaReader();
    }
}

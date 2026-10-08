package com.monstergym.member;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Al arrancar, calcula search_key de los socios que no la tienen (creados por SQL, p. ej. el seed de dev). */
@Component
public class SearchKeyBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SearchKeyBackfill.class);

    private final MemberRepository members;

    public SearchKeyBackfill(MemberRepository members) {
        this.members = members;
    }

    @Override
    public void run(ApplicationArguments args) {
        int n = members.backfillSearchKeys();
        if (n > 0) log.info("Clave de búsqueda calculada para {} socio(s).", n);
    }
}

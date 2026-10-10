package com.monstergym.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Respaldo diario a la hora de app.backup.cron y, al arrancar, si hoy todavía no hay uno. */
@Component
public class BackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

    private final BackupService backups;
    private final boolean onStartup;

    public BackupScheduler(BackupService backups, @Value("${app.backup.on-startup:true}") boolean onStartup) {
        this.backups = backups;
        this.onStartup = onStartup;
    }

    @Scheduled(cron = "${app.backup.cron:0 30 23 * * *}", zone = "America/Mexico_City")
    public void daily() {
        try {
            backups.backupToday();
        } catch (RuntimeException e) {
            log.error("No se pudo crear el respaldo diario", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        if (!onStartup) return;
        try {
            backups.backupTodayIfMissing();
        } catch (RuntimeException e) {
            log.error("No se pudo crear el respaldo al arrancar", e);
        }
    }
}

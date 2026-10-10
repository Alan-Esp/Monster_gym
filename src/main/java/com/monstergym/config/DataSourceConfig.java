package com.monstergym.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DataSourceConfig {

    @Value("${app.db.path:./monster-gym.db}")
    private String dbPath;

    @Bean
    public DataSource dataSource() throws IOException {
        // En una PC nueva la carpeta de datos todavía no existe (SQLite no la crea)
        Path parent = Path.of(dbPath).toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        SQLiteConfig sqLiteConfig = new SQLiteConfig();
        sqLiteConfig.enforceForeignKeys(true);
        sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);

        SQLiteDataSource sqLiteDataSource = new SQLiteDataSource(sqLiteConfig);
        sqLiteDataSource.setUrl("jdbc:sqlite:" + dbPath);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDataSource(sqLiteDataSource);
        hikariConfig.setMaximumPoolSize(1);

        return new HikariDataSource(hikariConfig);
    }
}

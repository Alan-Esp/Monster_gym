package com.monstergym.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Value("${app.db.path:./monster-gym.db}")
    private String dbPath;

    @Bean
    public DataSource dataSource() {
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

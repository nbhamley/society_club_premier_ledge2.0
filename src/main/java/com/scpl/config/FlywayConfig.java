package com.scpl.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * SQLite creates the database file but not its directory, so a fresh checkout would fail with
 * "unable to open database file". The directory is created just before the first migration.
 * Flyway is the only thing that creates tables: Hibernate runs in validate mode (§5).
 */
@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);
    private static final String PREFIX = "jdbc:sqlite:";

    @Bean
    FlywayMigrationStrategy migrationStrategy(@Value("${spring.datasource.url:}") String url) {
        return flyway -> {
            ensureSqliteDirectory(url);
            flyway.migrate();
        };
    }

    static void ensureSqliteDirectory(String url) {
        if (url == null || !url.startsWith(PREFIX)) {
            return;
        }
        String file = url.substring(PREFIX.length());
        int q = file.indexOf('?');
        if (q >= 0) {
            file = file.substring(0, q);
        }
        if (file.isBlank() || file.startsWith(":memory:") || file.startsWith("file::memory:")) {
            return;
        }
        if (file.startsWith("file:")) {
            file = file.substring("file:".length());
        }
        Path parent = Paths.get(file).toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            log.error("Cannot create the database directory {}", parent);
            throw new UncheckedIOException(e);
        }
    }
}
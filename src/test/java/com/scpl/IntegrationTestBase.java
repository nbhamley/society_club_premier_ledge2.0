package com.scpl;

import com.scpl.security.ScplUserDetailsService;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * Every integration test extends this class, so they all share one cached Spring context (and one database). The
 * database, uploads and backups live in a temp directory that is deleted when the JVM exits.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    private static final Path DIR = createTempDir();

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ScplUserDetailsService userDetailsService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DIR.resolve("scpl.db").toString().replace('\\', '/'));
        registry.add("scpl.uploads.dir", () -> DIR.resolve("uploads").toString());
        registry.add("scpl.backup.dir", () -> DIR.resolve("backups").toString());
        registry.add("scpl.backup.society-dir", () -> DIR.resolve("society-backups").toString());
    }

    /** Signs a MockMvc request in as a seeded user, through the real UserDetailsService (password "Passw0rd!"). */
    protected RequestPostProcessor as(String username) {
        return user(userDetailsService.loadUserByUsername(username));
    }

    private static Path createTempDir() {
        try {
            Path dir = Files.createTempDirectory("scpl-it-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> FileUtils.deleteQuietly(dir.toFile())));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
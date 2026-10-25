package com.scpl.integration.db;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Writes one gzip-compressed dump of the whole database (§10).
 *
 * <p>SQLite: VACUUM INTO gives a consistent snapshot from a single read transaction and, in WAL mode, does not block
 * writers (sqlite-jdbc's "backup to" restarts whenever another connection writes mid-copy). MySQL / PostgreSQL run
 * mysqldump / pg_dump without a shell: arguments are passed as an array, the database name and host are validated, and
 * the password travels in the process environment, never on the command line.
 *
 * <p>The output is written to a temporary name, verified, then moved into place, so a crash never leaves a half-written
 * file under the real name. Dumps are not encrypted (the spec's file name is .sqlite.gz); files are made owner-only
 * where the file system supports it.
 */
@Component
public class DatabaseDumper {

    public enum Kind {
        SQLITE("sqlite.gz"), MYSQL("sql.gz"), POSTGRESQL("sql.gz");

        private final String extension;

        Kind(String extension) {
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }
    }

    public record Result(Path file, long bytes, String sha256, Kind kind) {
    }

    private static final Logger log = LoggerFactory.getLogger(DatabaseDumper.class);
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern SAFE_DB_NAME = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$");
    private static final Pattern SAFE_HOST = Pattern.compile("^[A-Za-z0-9\\[][A-Za-z0-9_.:\\[\\]-]{0,254}$");

    private final DataSource dataSource;
    private final String url;
    private final String username;
    private final String password;
    private final String mysqldump;
    private final String pgdump;
    private final long timeoutMinutes;

    public DatabaseDumper(DataSource dataSource,
                          @Value("${spring.datasource.url:}") String url,
                          @Value("${spring.datasource.username:}") String username,
                          @Value("${spring.datasource.password:}") String password,
                          @Value("${scpl.backup.mysqldump-path:mysqldump}") String mysqldump,
                          @Value("${scpl.backup.pgdump-path:pg_dump}") String pgdump,
                          @Value("${scpl.backup.dump-timeout-minutes:30}") long timeoutMinutes) {
        this.dataSource = dataSource;
        this.url = url == null ? "" : url;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.mysqldump = mysqldump;
        this.pgdump = pgdump;
        this.timeoutMinutes = Math.max(1, timeoutMinutes);
    }

    /** Null when the configured database is not one this class knows how to dump. */
    public Kind kind() {
        if (url.startsWith("jdbc:sqlite:")) {
            return Kind.SQLITE;
        }
        if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) {
            return Kind.MYSQL;
        }
        if (url.startsWith("jdbc:postgresql:")) {
            return Kind.POSTGRESQL;
        }
        return null;
    }

    public Result dump(Path dir, LocalDate date) throws IOException {
        Kind kind = kind();
        if (kind == null) {
            throw new IOException("This database type cannot be dumped by the nightly job");
        }
        Files.createDirectories(dir);
        Path target = dir.resolve(date + "-full." + kind.extension()).normalize();
        Path rawTmp = dir.resolve(date + "-full.raw.tmp");
        Path gzTmp = dir.resolve(target.getFileName() + ".tmp");
        try {
            if (kind == Kind.SQLITE) {
                Files.deleteIfExists(rawTmp); // VACUUM INTO refuses to overwrite
                vacuumInto(rawTmp);
                try (InputStream in = Files.newInputStream(rawTmp);
                     OutputStream out = new GZIPOutputStream(Files.newOutputStream(gzTmp))) {
                    in.transferTo(out);
                }
            } else {
                runDumpTool(kind, dir, gzTmp);
            }
            ownerOnly(gzTmp);
            verify(gzTmp, kind);
            String sha = sha256(gzTmp);
            long size = Files.size(gzTmp);
            Files.move(gzTmp, target, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(target.resolveSibling(target.getFileName() + ".sha256"), sha, StandardCharsets.US_ASCII);
            log.info("Database dump written: {} ({})", target.getFileName(), FileUtils.byteCountToDisplaySize(size));
            return new Result(target, size, sha, kind);
        } finally {
            FileUtils.deleteQuietly(rawTmp.toFile());
            FileUtils.deleteQuietly(gzTmp.toFile());
        }
    }

    // ------------------------------------------------------------------ sqlite

    private void vacuumInto(Path destination) throws IOException {
        // The path is generated by this class, but quotes are doubled anyway.
        String literal = destination.toAbsolutePath().toString().replace("'", "''");
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(true); // VACUUM cannot run inside a transaction
            st.execute("VACUUM INTO '" + literal + "'");
        } catch (SQLException e) {
            throw new IOException("SQLite snapshot failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ mysql / postgresql

    private void runDumpTool(Kind kind, Path dir, Path gzTarget) throws IOException {
        URI uri;
        try {
            uri = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException e) {
            throw new IOException("The JDBC URL cannot be used for dumping (multi-host URLs are not supported)");
        }
        String host = uri.getHost();
        String db = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (host == null || !SAFE_HOST.matcher(host).matches() || !SAFE_DB_NAME.matcher(db).matches()) {
            throw new IOException("The database host or name in the JDBC URL is not usable for dumping");
        }
        List<String> cmd = new ArrayList<>();
        String passwordVariable;
        if (kind == Kind.MYSQL) {
            int port = uri.getPort() > 0 ? uri.getPort() : 3306;
            cmd.addAll(Arrays.asList(mysqldump, "--single-transaction", "--routines", "--host=" + host,
                    "--port=" + port, "--user=" + username, db));
            passwordVariable = "MYSQL_PWD";
        } else {
            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            cmd.addAll(Arrays.asList(pgdump, "--no-password", "--host=" + host, "--port=" + port,
                    "--username=" + username, "--dbname=" + db));
            passwordVariable = "PGPASSWORD";
        }
        Path errFile = Files.createTempFile(dir, "dump-err", ".tmp");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (!password.isEmpty()) {
            pb.environment().put(passwordVariable, password);
        }
        pb.redirectError(errFile.toFile());
        Process process = pb.start();
        ExecutorService pump = Executors.newSingleThreadExecutor();
        try {
            Future<Void> copy = pump.submit(() -> {
                try (InputStream in = process.getInputStream();
                     OutputStream out = new GZIPOutputStream(Files.newOutputStream(gzTarget))) {
                    in.transferTo(out);
                }
                return null;
            });
            copy.get(timeoutMinutes, TimeUnit.MINUTES);
            if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0) {
                throw new IOException("The dump tool failed: " + tail(errFile));
            }
        } catch (TimeoutException e) {
            throw new IOException("The dump tool timed out after " + timeoutMinutes + " minutes");
        } catch (ExecutionException e) {
            throw new IOException("Writing the dump failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("The dump was interrupted");
        } finally {
            pump.shutdownNow();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            FileUtils.deleteQuietly(errFile.toFile());
        }
    }

    private static String tail(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            return text.length() > 500 ? text.substring(text.length() - 500) : text;
        } catch (IOException e) {
            return "(no error output)";
        }
    }

    // ------------------------------------------------------------------ checks

    private static void verify(Path gz, Kind kind) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
            byte[] head = in.readNBytes(SQLITE_MAGIC.length);
            boolean ok = kind == Kind.SQLITE ? Arrays.equals(head, SQLITE_MAGIC) : head.length > 0;
            if (!ok) {
                throw new IOException("The dump failed its self-check and was discarded");
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest())) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Dumps hold every tenant's data, so rw------- where POSIX permissions exist (ignored on Windows). */
    private static void ownerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // best effort
        }
    }
}
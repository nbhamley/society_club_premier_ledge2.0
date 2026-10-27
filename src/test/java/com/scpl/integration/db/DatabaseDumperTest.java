package com.scpl.integration.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseDumperTest {

    @TempDir
    Path dir;

    private static String url(Path file) {
        return "jdbc:sqlite:" + file.toString().replace('\\', '/');
    }

    private DatabaseDumper dumperFor(String jdbcUrl, SQLiteDataSource ds) {
        return new DatabaseDumper(ds, jdbcUrl, "", "", "mysqldump", "pg_dump", 1);
    }

    @Test
    void aSqliteSnapshotIsGzippedVerifiedChecksummedAndReadable() throws Exception {
        String sourceUrl = url(dir.resolve("source.db"));
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(sourceUrl);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table t(id integer primary key, v text)");
            s.execute("insert into t(v) values ('hello'), ('world')");
        }
        DatabaseDumper dumper = dumperFor(sourceUrl, ds);
        assertThat(dumper.kind()).isEqualTo(DatabaseDumper.Kind.SQLITE);

        Path out = dir.resolve("out");
        DatabaseDumper.Result result = dumper.dump(out, LocalDate.of(2026, 10, 25));

        assertThat(result.file().getFileName().toString()).isEqualTo("2026-10-25-full.sqlite.gz");
        assertThat(result.kind()).isEqualTo(DatabaseDumper.Kind.SQLITE);
        assertThat(result.bytes()).isEqualTo(Files.size(result.file())).isGreaterThan(0);
        Path sidecar = out.resolve("2026-10-25-full.sqlite.gz.sha256");
        assertThat(Files.readString(sidecar, StandardCharsets.US_ASCII)).isEqualTo(result.sha256());

        // no temporary files are left behind
        try (var files = Files.list(out)) {
            assertThat(files.map(p -> p.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("2026-10-25-full.sqlite.gz", "2026-10-25-full.sqlite.gz.sha256");
        }

        // the dump is a real database that holds the rows
        Path unpacked = dir.resolve("restored.db");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(result.file()))) {
            Files.copy(in, unpacked);
        }
        List<String> values = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url(unpacked));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select v from t order by id")) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        assertThat(values).containsExactly("hello", "world");
    }

    @Test
    void runningTwiceOnTheSameDayReplacesTheDump() throws Exception {
        String sourceUrl = url(dir.resolve("source.db"));
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(sourceUrl);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table t(id integer primary key)");
        }
        DatabaseDumper dumper = dumperFor(sourceUrl, ds);
        Path out = dir.resolve("out");
        dumper.dump(out, LocalDate.of(2026, 10, 25));
        DatabaseDumper.Result second = dumper.dump(out, LocalDate.of(2026, 10, 25));
        assertThat(second.file()).exists();
        try (var files = Files.list(out)) {
            assertThat(files.count()).isEqualTo(2);   // dump + checksum, not duplicates
        }
    }

    @Test
    void theDatabaseTypeIsRecognisedFromTheJdbcUrl() {
        SQLiteDataSource ds = new SQLiteDataSource();
        assertThat(dumperFor("jdbc:sqlite:x.db", ds).kind()).isEqualTo(DatabaseDumper.Kind.SQLITE);
        assertThat(dumperFor("jdbc:mysql://h/db", ds).kind()).isEqualTo(DatabaseDumper.Kind.MYSQL);
        assertThat(dumperFor("jdbc:mariadb://h/db", ds).kind()).isEqualTo(DatabaseDumper.Kind.MYSQL);
        assertThat(dumperFor("jdbc:postgresql://h/db", ds).kind()).isEqualTo(DatabaseDumper.Kind.POSTGRESQL);
        assertThat(dumperFor("jdbc:h2:mem:x", ds).kind()).isNull();
        assertThat(dumperFor("", ds).kind()).isNull();
    }

    @Test
    void anUnsupportedDatabaseIsRefused_andAnUnsafeHostOrNameNeverReachesAProcess() {
        SQLiteDataSource ds = new SQLiteDataSource();
        assertThatThrownBy(() -> dumperFor("jdbc:h2:mem:x", ds).dump(dir, LocalDate.now()))
                .isInstanceOf(java.io.IOException.class);
        // database names with shell or option characters are rejected before any command is built
        assertThatThrownBy(() -> dumperFor("jdbc:postgresql://localhost/db;rm -rf", ds).dump(dir.resolve("o"), LocalDate.now()))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> dumperFor("jdbc:mysql://localhost/--all-databases", ds).dump(dir.resolve("o2"), LocalDate.now()))
                .isInstanceOf(java.io.IOException.class);
    }
}
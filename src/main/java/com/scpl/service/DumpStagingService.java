package com.scpl.service;

import com.scpl.exception.BusinessRuleException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FileUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Full-database dump staging (§10, "Restore from backup" tile). This validates and stores an uploaded nightly dump with
 * its checksum; it never replaces the live database. Swapping a SQLite file under a running application (open
 * connections, WAL) corrupts it, so an operator applies a staged dump with the app stopped (see README, "Restoring").
 * File names are generated here, never taken from the client.
 */
@Service
@RequiredArgsConstructor
public class DumpStagingService {

    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String SUFFIX = ".sqlite.gz";

    public record StagedDump(String fileName, long bytes, String sizeLabel, String sha256, LocalDateTime stagedAt) {
    }

    private final AuditService audit;

    @Value("${scpl.backup.dir:./data/backups}")
    private String backupDir;
    @Value("${scpl.backup.max-dump-bytes:268435456}")
    private long maxBytes;

    public StagedDump stage(com.scpl.model.User actor, MultipartFile file) {
        Tenancy.requirePlatformAdmin(actor);
        if (file == null || file.isEmpty()) {
            throw new BusinessRuleException("Choose a .sqlite.gz dump file");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!original.endsWith(SUFFIX)) {
            throw new BusinessRuleException("Only " + SUFFIX + " dumps from the nightly job can be staged");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessRuleException("The dump is larger than " + FileUtils.byteCountToDisplaySize(maxBytes));
        }
        // Gzip must open, and the first 16 bytes inside must be the SQLite header. Nothing is unpacked beyond that.
        try (InputStream in = file.getInputStream(); GZIPInputStream gz = new GZIPInputStream(in)) {
            if (!Arrays.equals(gz.readNBytes(SQLITE_MAGIC.length), SQLITE_MAGIC)) {
                throw new BusinessRuleException("This is not a SQLite database dump");
            }
        } catch (ZipException e) {
            throw new BusinessRuleException("This is not a valid gzip file");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        LocalDateTime now = LocalDateTime.now();
        Path dir = stagedDir();
        Path target = dir.resolve("dump-" + now.format(STAMP) + SUFFIX).normalize();
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        if (!target.startsWith(dir)) {
            throw new IllegalStateException("Staging path escaped the staging directory");
        }
        try {
            Files.createDirectories(dir);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = file.getInputStream(); DigestInputStream din = new DigestInputStream(in, md)) {
                Files.copy(din, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha = HexFormat.of().formatHex(md.digest());
            Files.move(tmp, target);
            Files.writeString(sidecar(target), sha, StandardCharsets.US_ASCII);
            audit.record(actor, null, AuditActions.FULL_DUMP_STAGED, "DUMP", null, null,
                    Map.of("file", target.getFileName().toString(), "bytes", file.getSize(), "sha256", sha));
            return new StagedDump(target.getFileName().toString(), file.getSize(),
                    FileUtils.byteCountToDisplaySize(file.getSize()), sha, now);
        } catch (IOException | NoSuchAlgorithmException e) {
            FileUtils.deleteQuietly(tmp.toFile());
            throw new IllegalStateException("Could not stage the dump", e);
        }
    }

    public List<StagedDump> list(com.scpl.model.User actor) {
        Tenancy.requirePlatformAdmin(actor);
        Path dir = stagedDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                    .map(this::describe).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private StagedDump describe(Path p) {
        try {
            long size = Files.size(p);
            Path side = sidecar(p);
            String sha = Files.isRegularFile(side) ? Files.readString(side, StandardCharsets.US_ASCII).trim() : null;
            LocalDateTime when = LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault());
            return new StagedDump(p.getFileName().toString(), size, FileUtils.byteCountToDisplaySize(size), sha, when);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path sidecar(Path dump) {
        return dump.resolveSibling(dump.getFileName() + ".sha256");
    }

    private Path stagedDir() {
        return Paths.get(backupDir).toAbsolutePath().normalize().resolve("staged");
    }
}
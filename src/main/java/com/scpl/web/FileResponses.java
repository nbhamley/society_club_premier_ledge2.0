package com.scpl.web;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Attachment downloads. Files contain PII or backups, so they are never cached by browsers or proxies. */
final class FileResponses {

    private FileResponses() {
    }

    static ResponseEntity<Resource> attachment(Path file, String downloadName, MediaType type) {
        ContentDisposition disposition = ContentDisposition.attachment().filename(downloadName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.noStore()).contentType(type).body(new FileSystemResource(file));
    }

    static ResponseEntity<byte[]> bytes(byte[] data, String downloadName, MediaType type) {
        ContentDisposition disposition = ContentDisposition.attachment().filename(downloadName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .cacheControl(CacheControl.noStore()).contentType(type).body(data);
    }
}
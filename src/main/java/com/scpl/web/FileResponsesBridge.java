package com.scpl.web;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;

/** Public entry point to FileResponses for controllers outside this package (web.api). */
public final class FileResponsesBridge {

    private FileResponsesBridge() {
    }

    public static ResponseEntity<Resource> attachment(Path file, String downloadName, MediaType type) {
        return FileResponses.attachment(file, downloadName, type);
    }
}
package com.scpl.job;

import com.scpl.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Looks for UPLOADED import batches (§9). fixedDelay means the next run starts a minute after this one ENDS, so a slow
 * file or AI call never overlaps itself; the atomic UPLOADED to SEGREGATING claim in ImportService covers the rest.
 * Not @Transactional on purpose: ImportService keeps its own short transactions so SQLite's single writer is never held
 * during parsing.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "scpl.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ImportPollingJob {

    private static final Logger log = LoggerFactory.getLogger(ImportPollingJob.class);

    private final ImportService imports;

    @Scheduled(fixedDelayString = "${scpl.import.poll-delay-ms:60000}",
            initialDelayString = "${scpl.import.poll-initial-delay-ms:15000}")
    public void run() {
        try {
            int processed = imports.pollOnce();
            if (processed > 0) {
                log.info("Import poller processed {} batch(es)", processed);
            }
        } catch (RuntimeException e) {
            log.error("Import poller failed", e);
        }
    }
}
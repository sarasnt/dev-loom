package com.devloom.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic reconciliation sync (SPEC.md §14 — hybrid sync's polling half). Re-syncs every
 * enabled connector on a fixed interval so new work (e.g. a freshly opened GitHub PR, a new
 * Jira issue) appears without a manual re-sync or restart. Best-effort; failures are per
 * connector and never surface here.
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final SyncService sync;

    public SyncScheduler(SyncService sync) {
        this.sync = sync;
    }

    // Default every 5 minutes; first run after one interval (startup sync already ran at boot).
    @Scheduled(fixedDelayString = "${devloom.sync.interval-ms:300000}",
            initialDelayString = "${devloom.sync.interval-ms:300000}")
    public void reconcile() {
        try {
            int n = sync.syncAll();
            log.info("Scheduled reconciliation sync: {} items", n);
        } catch (Exception e) {
            log.warn("Scheduled sync error (ignored): {}", e.getMessage());
        }
    }
}

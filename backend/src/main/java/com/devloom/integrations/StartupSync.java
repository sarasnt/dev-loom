package com.devloom.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Runs an initial sync of enabled instances at boot (best-effort, never fails startup). */
@Component
@Order(2)
public class StartupSync implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSync.class);

    private final SyncService sync;
    private final boolean enabled;

    public StartupSync(SyncService sync,
                       @Value("${devloom.jira.sync-on-startup:true}") boolean enabled) {
        this.sync = sync;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            int n = sync.syncAll();
            log.info("Startup sync complete: {} items ingested from live connectors", n);
        } catch (Exception e) {
            log.warn("Startup sync error (ignored): {}", e.getMessage());
        }
    }
}

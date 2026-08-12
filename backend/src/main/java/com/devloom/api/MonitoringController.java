package com.devloom.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devloom.ai.ModelMonitor;
import com.devloom.common.AppConfigService;

/**
 * Model monitoring API (SPEC.md §25). Surfaces what {@link ModelMonitor} (a LangChain4j
 * ChatModelListener) has observed — recent calls, per-model totals, token usage — so the
 * Monitoring panel gives a way to see model activity without leaving DevLoom. The same data
 * is also exported as Micrometer meters at {@code /actuator/metrics/devloom.llm.*}.
 */
@RestController
@RequestMapping("/api/v1/monitoring")
public class MonitoringController {

    private final ModelMonitor monitor;
    private final AppConfigService config;
    private final boolean langfuseEnabled;

    public MonitoringController(ModelMonitor monitor, AppConfigService config,
                                @Value("${devloom.langfuse.enabled:false}") boolean langfuseEnabled) {
        this.monitor = monitor;
        this.config = config;
        this.langfuseEnabled = langfuseEnabled;
    }

    /**
     * @param windowDays how far back to report; 0 means everything still retained
     */
    @GetMapping("/models")
    public Map<String, Object> models(
            @RequestParam(name = "windowDays", defaultValue = "7") int windowDays) {
        Map<String, Object> out = new LinkedHashMap<>(monitor.snapshot(windowDays));
        out.put("langfuseEnabled", langfuseEnabled);
        out.put("metricsPath", "/actuator/metrics/devloom.llm.calls");
        return out;
    }

    public record Retention(int days) {}

    /** Set how long call history is kept. 0 keeps it indefinitely. */
    @PutMapping("/retention")
    public Map<String, Object> retention(@RequestBody Retention body) {
        int days = Math.max(0, Math.min(body == null ? 30 : body.days(), 3650));
        config.set(ModelMonitor.RETENTION_DAYS, String.valueOf(days));
        monitor.prune(); // apply the new window now rather than at the next hourly sweep
        return Map.of("retentionDays", days);
    }
}

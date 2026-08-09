package com.devloom.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devloom.ai.ModelMonitor;

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
    private final boolean langfuseEnabled;

    public MonitoringController(ModelMonitor monitor,
                                @Value("${devloom.langfuse.enabled:false}") boolean langfuseEnabled) {
        this.monitor = monitor;
        this.langfuseEnabled = langfuseEnabled;
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        Map<String, Object> out = new LinkedHashMap<>(monitor.snapshot());
        out.put("langfuseEnabled", langfuseEnabled);
        out.put("metricsPath", "/actuator/metrics/devloom.llm.calls");
        return out;
    }
}

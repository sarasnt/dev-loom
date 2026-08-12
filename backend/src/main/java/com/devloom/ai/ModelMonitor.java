package com.devloom.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.common.AppConfigService;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * LangChain4j model observability (SPEC.md §25). A single {@link ChatModelListener} attached to
 * every LangChain4j-backed adapter ({@link OllamaLlm}, {@link OpenAiLlm}, {@link AnthropicLlm}),
 * so every model call — local or remote — flows through here exactly once (listener methods fire
 * once per request regardless of retries).
 *
 * <p>Each call is recorded two ways: as Micrometer meters (visible at
 * {@code /actuator/metrics/devloom.llm.*} and any Prometheus/Grafana scrape) and as a row in
 * {@code llm_call}, which is what the Monitoring panel reads. The panel used to read an in-memory
 * ring, so the numbers reset to zero on every backend restart and described the current process
 * rather than your usage; the table makes it a history, pruned on a retention window. Token usage
 * comes straight from the provider's response metadata, so latency and token counts are real, not
 * estimates. Only measurements are stored — never prompt or response text.
 */
@Component
public class ModelMonitor implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(ModelMonitor.class);
    private static final Object START = new Object(); // attribute key for the request start time
    private static final int MAX_RECENT = 100;

    /** How long history is kept, unless overridden in Settings. */
    public static final String RETENTION_DAYS = "monitoring.retentionDays";
    private static final int DEFAULT_RETENTION_DAYS = 30;

    private final MeterRegistry meters;
    private final LangfuseTracer langfuse;
    private final LlmCallRepository calls;
    private final AppConfigService config;

    public ModelMonitor(MeterRegistry meters, LangfuseTracer langfuse,
                        LlmCallRepository calls, AppConfigService config) {
        this.meters = meters;
        this.langfuse = langfuse;
        this.calls = calls;
        this.config = config;
    }

    /** One observed model call (already redacted upstream — no prompt/response text kept). */
    public record Call(long at, String provider, String model, long latencyMs,
                       long inputTokens, long outputTokens, boolean ok, String error) {}

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        ctx.attributes().put(START, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        long latencyMs = elapsedMs(ctx.attributes().get(START));
        String provider = providerOf(ctx.modelProvider());
        ChatResponse response = ctx.chatResponse();
        String model = response.metadata() == null ? "?" : orDefault(response.metadata().modelName(), "?");
        long in = 0, out = 0;
        if (response.metadata() != null) {
            TokenUsage usage = response.metadata().tokenUsage();
            if (usage != null) {
                in = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
                out = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
            }
        }
        record(new Call(System.currentTimeMillis(), provider, model, latencyMs, in, out, true, null));
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        long latencyMs = elapsedMs(ctx.attributes().get(START));
        String provider = providerOf(ctx.modelProvider());
        String model = ctx.chatRequest() == null || ctx.chatRequest().parameters() == null
                ? "?" : orDefault(ctx.chatRequest().parameters().modelName(), "?");
        String msg = ctx.error() == null ? "error" : ctx.error().getMessage();
        record(new Call(System.currentTimeMillis(), provider, model, latencyMs, 0, 0, false, msg));
    }

    private void record(Call c) {
        // Meters (exported): count, latency, tokens — tagged by provider + model + outcome.
        Tags base = Tags.of("provider", c.provider(), "model", c.model());
        meters.counter("devloom.llm.calls", base.and("outcome", c.ok() ? "success" : "error")).increment();
        meters.timer("devloom.llm.latency", base).record(Duration.ofMillis(c.latencyMs()));
        if (c.inputTokens() > 0) meters.counter("devloom.llm.tokens", base.and("type", "input")).increment(c.inputTokens());
        if (c.outputTokens() > 0) meters.counter("devloom.llm.tokens", base.and("type", "output")).increment(c.outputTokens());

        // Persist for the Monitoring panel. Observing a call must never be able to fail it, so a
        // database problem is logged and dropped rather than thrown back into the model path.
        try {
            calls.save(LlmCallEntity.of(Instant.ofEpochMilli(c.at()), c.provider(), c.model(),
                    c.latencyMs(), c.inputTokens(), c.outputTokens(), c.ok(), c.error()));
        } catch (Exception e) {
            log.warn("Could not record model call for monitoring: {}", e.toString());
        }

        log.info("llm-monitor provider={} model={} latencyMs={} in={} out={} ok={}",
                c.provider(), c.model(), c.latencyMs(), c.inputTokens(), c.outputTokens(), c.ok());

        // Export to Langfuse (no-op unless enabled + keys present); async, never blocks.
        langfuse.export(c.provider(), c.model(), c.at(), c.latencyMs(),
                c.inputTokens(), c.outputTokens(), c.ok(), c.error());
    }

    /**
     * Snapshot for the API over a time window: per-model totals + the most recent calls.
     *
     * @param windowDays how far back to look; {@code <= 0} means the whole retained history
     */
    public Map<String, Object> snapshot(int windowDays) {
        Instant since = windowDays <= 0 ? Instant.EPOCH : Instant.now().minus(Duration.ofDays(windowDays));

        List<Map<String, Object>> recent = new ArrayList<>();
        for (LlmCallEntity c : calls.findByAtGreaterThanEqualOrderByAtDesc(since, Limit.of(MAX_RECENT))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", c.getAt().toEpochMilli());
            m.put("provider", c.getProvider());
            m.put("model", c.getModel());
            m.put("latencyMs", c.getLatencyMs());
            m.put("inputTokens", c.getInputTokens());
            m.put("outputTokens", c.getOutputTokens());
            m.put("ok", c.isOk());
            m.put("error", c.getError());
            recent.add(m);
        }

        List<Map<String, Object>> models = new ArrayList<>();
        long totalCalls = 0, totalErrors = 0, totalIn = 0, totalOut = 0;
        for (Object[] row : calls.aggregateSince(since)) {
            long n = num(row[2]), errors = num(row[3]), in = num(row[5]), out = num(row[6]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", row[0]);
            m.put("model", row[1]);
            m.put("calls", n);
            m.put("errors", errors);
            m.put("avgLatencyMs", num(row[4]));
            m.put("inputTokens", in);
            m.put("outputTokens", out);
            models.add(m);
            totalCalls += n; totalErrors += errors; totalIn += in; totalOut += out;
        }

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("calls", totalCalls);
        totals.put("errors", totalErrors);
        totals.put("inputTokens", totalIn);
        totals.put("outputTokens", totalOut);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", models);
        out.put("recent", recent);
        out.put("totals", totals);
        out.put("windowDays", Math.max(windowDays, 0));
        out.put("retentionDays", retentionDays());
        return out;
    }

    /** Days of history kept. 0 disables pruning — history then grows until you change it. */
    public int retentionDays() {
        return config.get(RETENTION_DAYS)
                .map(v -> { try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return DEFAULT_RETENTION_DAYS; } })
                .orElse(DEFAULT_RETENTION_DAYS);
    }

    /**
     * Prune history past the retention window. Hourly rather than on write: pruning inside a model
     * call would make some calls mysteriously slower than others.
     */
    @Scheduled(fixedRate = 3_600_000, initialDelay = 60_000)
    @Transactional
    public void prune() {
        int days = retentionDays();
        if (days <= 0) return;
        try {
            int removed = calls.deleteOlderThan(Instant.now().minus(Duration.ofDays(days)));
            if (removed > 0) log.info("Pruned {} model calls older than {} days", removed, days);
        } catch (Exception e) {
            log.warn("Could not prune monitoring history: {}", e.toString());
        }
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static long elapsedMs(Object start) {
        return start instanceof Long s ? (System.nanoTime() - s) / 1_000_000 : 0;
    }

    private static String providerOf(Object modelProvider) {
        return modelProvider == null ? "?" : modelProvider.toString().toLowerCase();
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }
}

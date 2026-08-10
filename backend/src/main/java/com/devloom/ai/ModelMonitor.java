package com.devloom.ai;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 * {@code /actuator/metrics/devloom.llm.*} and any Prometheus/Grafana scrape) and as a small
 * in-memory ring of recent calls + per-model aggregates that the Monitoring panel reads. Token
 * usage comes straight from the provider's response metadata, so latency and token counts are
 * real, not estimates. Redaction happens upstream, so nothing sensitive is stored here.
 */
@Component
public class ModelMonitor implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(ModelMonitor.class);
    private static final Object START = new Object(); // attribute key for the request start time
    private static final int MAX_RECENT = 100;

    private final MeterRegistry meters;
    private final LangfuseTracer langfuse;
    private final Deque<Call> recent = new ArrayDeque<>();
    private final Map<String, Agg> byModel = new LinkedHashMap<>();

    public ModelMonitor(MeterRegistry meters, LangfuseTracer langfuse) {
        this.meters = meters;
        this.langfuse = langfuse;
    }

    /** One observed model call (already redacted upstream — no prompt/response text kept). */
    public record Call(long at, String provider, String model, long latencyMs,
                       long inputTokens, long outputTokens, boolean ok, String error) {}

    private static final class Agg {
        String provider, model;
        long calls, errors, totalLatencyMs, inputTokens, outputTokens;
    }

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

    private synchronized void record(Call c) {
        // Meters (exported): count, latency, tokens — tagged by provider + model + outcome.
        Tags base = Tags.of("provider", c.provider(), "model", c.model());
        meters.counter("devloom.llm.calls", base.and("outcome", c.ok() ? "success" : "error")).increment();
        meters.timer("devloom.llm.latency", base).record(Duration.ofMillis(c.latencyMs()));
        if (c.inputTokens() > 0) meters.counter("devloom.llm.tokens", base.and("type", "input")).increment(c.inputTokens());
        if (c.outputTokens() > 0) meters.counter("devloom.llm.tokens", base.and("type", "output")).increment(c.outputTokens());

        // In-memory ring + aggregates for the Monitoring panel.
        recent.addFirst(c);
        while (recent.size() > MAX_RECENT) recent.removeLast();
        Agg a = byModel.computeIfAbsent(c.provider() + "/" + c.model(), k -> {
            Agg x = new Agg(); x.provider = c.provider(); x.model = c.model(); return x;
        });
        a.calls++;
        if (!c.ok()) a.errors++;
        a.totalLatencyMs += c.latencyMs();
        a.inputTokens += c.inputTokens();
        a.outputTokens += c.outputTokens();

        log.info("llm-monitor provider={} model={} latencyMs={} in={} out={} ok={}",
                c.provider(), c.model(), c.latencyMs(), c.inputTokens(), c.outputTokens(), c.ok());

        // Export to Langfuse (no-op unless enabled + keys present); async, never blocks.
        langfuse.export(c.provider(), c.model(), c.at(), c.latencyMs(),
                c.inputTokens(), c.outputTokens(), c.ok(), c.error());
    }

    /** Snapshot for the API: recent calls (newest first) + per-model totals. */
    public synchronized Map<String, Object> snapshot() {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (Call c : recent) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", c.at());
            m.put("provider", c.provider());
            m.put("model", c.model());
            m.put("latencyMs", c.latencyMs());
            m.put("inputTokens", c.inputTokens());
            m.put("outputTokens", c.outputTokens());
            m.put("ok", c.ok());
            m.put("error", c.error());
            calls.add(m);
        }
        List<Map<String, Object>> models = new ArrayList<>();
        long totalCalls = 0, totalErrors = 0, totalIn = 0, totalOut = 0;
        for (Agg a : byModel.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", a.provider);
            m.put("model", a.model);
            m.put("calls", a.calls);
            m.put("errors", a.errors);
            m.put("avgLatencyMs", a.calls == 0 ? 0 : a.totalLatencyMs / a.calls);
            m.put("inputTokens", a.inputTokens);
            m.put("outputTokens", a.outputTokens);
            models.add(m);
            totalCalls += a.calls; totalErrors += a.errors; totalIn += a.inputTokens; totalOut += a.outputTokens;
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("calls", totalCalls);
        totals.put("errors", totalErrors);
        totals.put("inputTokens", totalIn);
        totals.put("outputTokens", totalOut);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", models);
        out.put("recent", calls);
        out.put("totals", totals);
        return out;
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

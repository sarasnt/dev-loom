package com.devloom.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LLM observability export to a self-hosted Langfuse (SPEC.md §25). Fed by {@link ModelMonitor}
 * (the LangChain4j ChatModelListener), so every model call it observes becomes a Langfuse trace
 * with a nested generation carrying model, token usage, latency and outcome.
 *
 * <p>Export is off unless {@code devloom.langfuse.enabled=true} and a public/secret key pair is
 * configured; then each call is POSTed to Langfuse's batched {@code /api/public/ingestion}
 * endpoint (Basic auth: public key = user, secret key = password). Sending is async and
 * fire-and-forget — a Langfuse hiccup never slows or breaks a model call. Redaction happens
 * upstream, so we send metrics (tokens/latency/model), not prompt or response text.
 *
 * <p>The current {@code feature} (brainstorm, build-analysis, …) is carried on a thread-local set
 * by {@link LlmRouter} around the generate call, so the Langfuse trace is named by feature.
 */
@Component
public class LangfuseTracer {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTracer.class);

    private final boolean enabled;
    private final String ingestUrl;
    private final String authHeader;     // "Basic base64(pk:sk)" or null when keys absent
    private final HttpClient http;
    private final ThreadLocal<String> feature = new ThreadLocal<>();

    public LangfuseTracer(
            @Value("${devloom.langfuse.enabled:false}") boolean enabled,
            @Value("${devloom.langfuse.host:http://localhost:3000}") String host,
            @Value("${devloom.langfuse.public-key:}") String publicKey,
            @Value("${devloom.langfuse.secret-key:}") String secretKey) {
        this.enabled = enabled;
        String h = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        this.ingestUrl = h + "/api/public/ingestion";
        boolean haveKeys = publicKey != null && !publicKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
        this.authHeader = haveKeys
                ? "Basic " + Base64.getEncoder().encodeToString(
                        (publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8))
                : null;
        // Force HTTP/1.1: the default HTTP/2 cleartext negotiation fails against Langfuse's
        // server ("header parser received no bytes").
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        if (enabled && !haveKeys) {
            log.warn("Langfuse export enabled but public/secret key missing — set "
                    + "DEVLOOM_LANGFUSE_PUBLIC_KEY / DEVLOOM_LANGFUSE_SECRET_KEY. Traces won't be sent.");
        }
    }

    /** True when calls will actually be exported (enabled + keys present). */
    public boolean exporting() {
        return enabled && authHeader != null;
    }

    /** LlmRouter marks the current feature so exported traces are named by it (brainstorm, …). */
    public void setFeature(String f) {
        if (f != null) feature.set(f);
    }

    public void clearFeature() {
        feature.remove();
    }

    /** Local breadcrumb log for every routed call (kept even when export is off). */
    public void trace(String feature, String provider, String model, long latencyMs, boolean hypothesis) {
        log.info("llm-trace feature={} provider={} model={} latencyMs={} hypothesis={}",
                feature, provider, model, latencyMs, hypothesis);
    }

    /**
     * Export one observed model call to Langfuse as a trace + generation. Fire-and-forget.
     *
     * @param endEpochMs when the call finished (ms since epoch); start = end - latency
     */
    public void export(String provider, String model, long endEpochMs, long latencyMs,
                       long inputTokens, long outputTokens, boolean ok, String error) {
        if (!exporting()) return;
        try {
            String traceId = UUID.randomUUID().toString();
            String feat = feature.get();
            String traceName = (feat == null || feat.isBlank()) ? "devloom-llm" : feat;
            String end = Instant.ofEpochMilli(endEpochMs).toString();
            String start = Instant.ofEpochMilli(Math.max(0, endEpochMs - latencyMs)).toString();
            String body = buildBatch(traceId, traceName, provider, model, start, end,
                    inputTokens, outputTokens, ok, error);

            HttpRequest req = HttpRequest.newBuilder(URI.create(ingestUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((resp, ex) -> {
                        if (ex != null) {
                            log.warn("Langfuse export failed: {}", ex.getMessage());
                        } else if (resp.statusCode() >= 300) {
                            log.warn("Langfuse export HTTP {}: {}", resp.statusCode(), resp.body());
                        }
                    });
        } catch (Exception e) {
            log.warn("Langfuse export error: {}", e.getMessage());
        }
    }

    /**
     * Export one scored run: a trace for the run as a whole, plus Langfuse scores on it — the
     * overall quality, and one per penalty so a bad behaviour can be filtered and counted rather
     * than only showing up as a lower number.
     *
     * <p>This is a trace of the RUN, distinct from the per-call generations {@link #export} sends.
     * A run with four tool steps makes four model calls, and the thing worth scoring is the run.
     */
    public void exportScore(String feature, String provider, String model,
                            long startMs, long endMs, RunQuality.Score score, ToolTelemetry tel) {
        if (!exporting()) return;
        try {
            String traceId = UUID.randomUUID().toString();
            String name = (feature == null || feature.isBlank() ? "devloom" : feature) + "-run";
            StringBuilder events = new StringBuilder();

            events.append("{")
                    .append("\"id\":\"").append(UUID.randomUUID()).append("\",")
                    .append("\"type\":\"trace-create\",")
                    .append("\"timestamp\":\"").append(Instant.ofEpochMilli(endMs)).append("\",")
                    .append("\"body\":{")
                    .append("\"id\":\"").append(traceId).append("\",")
                    .append("\"name\":\"").append(esc(name)).append("\",")
                    .append("\"timestamp\":\"").append(Instant.ofEpochMilli(startMs)).append("\",")
                    .append("\"metadata\":{")
                    .append("\"provider\":\"").append(esc(provider)).append("\",")
                    .append("\"model\":\"").append(esc(model)).append("\",")
                    .append("\"steps\":").append(tel.steps()).append(',')
                    .append("\"toolCalls\":").append(tel.toolCalls()).append(',')
                    .append("\"repeatedCalls\":").append(tel.repeatedCalls()).append(',')
                    .append("\"unknownTools\":").append(tel.unknownTools()).append(',')
                    .append("\"toolErrors\":").append(tel.toolErrors()).append(',')
                    .append("\"hitStepCap\":").append(tel.didHitStepCap()).append(',')
                    .append("\"stoppedSpinning\":").append(tel.didStopSpinning()).append(',')
                    .append("\"grounded\":").append(tel.gathered())
                    .append("}}}");

            events.append(',').append(scoreEvent(traceId, "quality", score.value(), score.summary(), endMs));
            for (RunQuality.Penalty p : score.penalties()) {
                // Negative, so a Langfuse chart of a penalty reads as the cost it imposed.
                events.append(',').append(scoreEvent(traceId, "penalty." + p.name(), -p.cost(), p.detail(), endMs));
            }

            post("{\"batch\":[" + events + "]}");
        } catch (Exception e) {
            log.warn("Langfuse score export error: {}", e.getMessage());
        }
    }

    private static String scoreEvent(String traceId, String name, double value, String comment, long atMs) {
        return "{"
                + "\"id\":\"" + UUID.randomUUID() + "\","
                + "\"type\":\"score-create\","
                + "\"timestamp\":\"" + Instant.ofEpochMilli(atMs) + "\","
                + "\"body\":{"
                + "\"id\":\"" + UUID.randomUUID() + "\","
                + "\"traceId\":\"" + traceId + "\","
                + "\"name\":\"" + esc(name) + "\","
                + "\"value\":" + value + ","
                + "\"dataType\":\"NUMERIC\","
                + "\"comment\":\"" + esc(comment == null ? "" : comment) + "\""
                + "}}";
    }

    /** Fire-and-forget POST to the ingestion endpoint. */
    private void post(String body) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(ingestUrl))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, ex) -> {
                    if (ex != null) log.warn("Langfuse export failed: {}", ex.getMessage());
                    else if (resp.statusCode() >= 300) log.warn("Langfuse export HTTP {}: {}", resp.statusCode(), resp.body());
                });
    }

    /** Batched ingestion payload: a trace-create + a generation-create referencing it. */
    private static String buildBatch(String traceId, String traceName, String provider, String model,
                                     String start, String end, long in, long out, boolean ok, String error) {
        String genId = UUID.randomUUID().toString();
        String level = ok ? "DEFAULT" : "ERROR";
        String statusMessage = ok || error == null ? "" : ",\"statusMessage\":\"" + esc(error) + "\"";
        String genMeta = "{\"provider\":\"" + esc(provider) + "\"}";
        String traceMeta = "{\"provider\":\"" + esc(provider) + "\",\"source\":\"devloom\"}";

        String traceEvent = "{"
                + "\"id\":\"" + UUID.randomUUID() + "\","
                + "\"type\":\"trace-create\","
                + "\"timestamp\":\"" + end + "\","
                + "\"body\":{"
                + "\"id\":\"" + traceId + "\","
                + "\"name\":\"" + esc(traceName) + "\","
                + "\"timestamp\":\"" + start + "\","
                + "\"metadata\":" + traceMeta
                + "}}";

        String genEvent = "{"
                + "\"id\":\"" + UUID.randomUUID() + "\","
                + "\"type\":\"generation-create\","
                + "\"timestamp\":\"" + end + "\","
                + "\"body\":{"
                + "\"id\":\"" + genId + "\","
                + "\"traceId\":\"" + traceId + "\","
                + "\"type\":\"GENERATION\","
                + "\"name\":\"" + esc(model) + "\","
                + "\"startTime\":\"" + start + "\","
                + "\"endTime\":\"" + end + "\","
                + "\"model\":\"" + esc(model) + "\","
                + "\"usage\":{\"input\":" + in + ",\"output\":" + out + ",\"total\":" + (in + out) + ",\"unit\":\"TOKENS\"},"
                + "\"level\":\"" + level + "\","
                + "\"metadata\":" + genMeta
                + statusMessage
                + "}}";

        return "{\"batch\":[" + traceEvent + "," + genEvent + "]}";
    }

    /** Minimal JSON string escaping for the values we emit (model names, provider, error text). */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }
}

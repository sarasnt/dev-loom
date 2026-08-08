package com.devloom.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LLM observability seam (SPEC.md §25). Every LLM call routes a trace through here:
 * feature, provider, model, latency, and whether the output is a hypothesis. When
 * {@code devloom.langfuse.enabled=true} this is where OTLP/HTTP export to a self-hosted
 * Langfuse plugs in; disabled (default) it logs locally. Redaction happens upstream
 * (context is already redacted before it reaches an LlmPort), so traces carry no secrets.
 */
@Component
public class LangfuseTracer {

    private static final Logger log = LoggerFactory.getLogger(LangfuseTracer.class);

    private final boolean enabled;

    public LangfuseTracer(@Value("${devloom.langfuse.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public void trace(String feature, String provider, String model, long latencyMs, boolean hypothesis) {
        log.info("llm-trace feature={} provider={} model={} latencyMs={} hypothesis={}",
                feature, provider, model, latencyMs, hypothesis);
        if (enabled) {
            // TODO: export as an OTLP/HTTP span to self-hosted Langfuse
            // (OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf). Seam is ready; exporter wiring
            // is deferred until a Langfuse container is configured.
            log.debug("langfuse export enabled — span would be sent for feature={}", feature);
        }
    }
}

package com.devloom.ai;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.devloom.audit.AuditService;

/**
 * The single place feature code obtains an LLM (SPEC.md §20). Prefers the first available
 * real provider (local Ollama, or a BYO adapter later); falls back to the deterministic
 * offline {@link StubLlm}. Traces every call. Feature code depends on this, never on a
 * concrete provider — so swapping/adding providers changes nothing downstream.
 */
@Service
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final List<LlmPort> ports;
    private final LangfuseTracer tracer;
    private final AuditService audit;

    public LlmRouter(List<LlmPort> ports, LangfuseTracer tracer, AuditService audit) {
        this.ports = ports;
        this.tracer = tracer;
        this.audit = audit;
    }

    private static boolean isRemote(String provider) {
        return provider != null && !provider.equals("stub") && !provider.equals("ollama");
    }

    /** Is a real (non-stub) model reachable right now? */
    public boolean hasRealModel() {
        return realProvider().isPresent();
    }

    private Optional<LlmPort> realProvider() {
        return ports.stream()
                .filter(p -> !"stub".equals(p.provider()))
                .filter(LlmPort::available)
                .findFirst();
    }

    /**
     * Generate via the best available provider, tracing latency/provider/model. Resilient:
     * if the real provider errors or returns nothing (model missing, server hiccup), fall
     * back to the offline stub so a feature never fails on an LLM problem.
     */
    public LlmPort.LlmResult generate(LlmPort.LlmRequest request) {
        Optional<LlmPort> real = realProvider();
        if (real.isPresent()) {
            long t0 = System.currentTimeMillis();
            try {
                LlmPort.LlmResult result = real.get().generate(request);
                if (result.text() != null && !result.text().isBlank()) {
                    tracer.trace(request.feature(), result.provider(), result.model(),
                            System.currentTimeMillis() - t0, result.hypothesis());
                    // Record egress only when data actually left the machine (remote provider).
                    if (isRemote(result.provider())) {
                        audit.record("llm-egress", result.provider(), "feature=" + request.feature());
                    }
                    return result;
                }
            } catch (Exception e) {
                log.warn("LLM provider '{}' failed for feature '{}': {} — falling back to stub",
                        real.get().provider(), request.feature(), e.getMessage());
            }
        }
        long t0 = System.currentTimeMillis();
        LlmPort.LlmResult result = stub().generate(request);
        tracer.trace(request.feature(), result.provider(), result.model(),
                System.currentTimeMillis() - t0, result.hypothesis());
        return result;
    }

    private LlmPort stub() {
        return ports.stream().filter(p -> "stub".equals(p.provider())).findFirst().orElseThrow();
    }
}

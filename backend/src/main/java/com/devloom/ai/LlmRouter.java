package com.devloom.ai;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.devloom.audit.AuditService;

/**
 * The single place feature code obtains an LLM (SPEC.md §20). Routes by the requested (or
 * user-selected) model to the right provider — {@code claude-*} → Anthropic, {@code gpt-*}/
 * {@code o*} → OpenAI, everything else → local Ollama — and falls back to the offline
 * {@link StubLlm}. Remote (paid) calls are budget-gated and their egress is audited. Feature
 * code depends only on this, never on a concrete provider.
 */
@Service
public class LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmRouter.class);

    private final List<LlmPort> ports;
    private final LangfuseTracer tracer;
    private final AuditService audit;
    private final ModelPreference modelPref;
    private final CostBudget budget;
    private final SkillContext skills;

    public LlmRouter(List<LlmPort> ports, LangfuseTracer tracer, AuditService audit,
                     ModelPreference modelPref, CostBudget budget, SkillContext skills) {
        this.ports = ports;
        this.tracer = tracer;
        this.audit = audit;
        this.modelPref = modelPref;
        this.budget = budget;
        this.skills = skills;
    }

    private static boolean isRemote(String provider) {
        return provider != null && !provider.equals("stub") && !provider.equals("ollama");
    }

    /** Which provider serves a model name. "claude-code" = CLI subscription; "gpt-oss" = local. */
    public static String providerForModel(String model) {
        if (model == null) return null;
        String m = model.toLowerCase();
        if (m.equals("claude-code") || m.equals("claude-cli")) return "claude-code";
        if (m.startsWith("claude")) return "anthropic";
        if (m.startsWith("gpt-oss")) return "ollama";
        if (m.startsWith("gpt-") || m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")) return "openai";
        return "ollama";
    }

    /** Is a real (non-stub) model reachable right now? */
    public boolean hasRealModel() {
        return localRealProvider().isPresent()
                || ports.stream().anyMatch(p -> isRemote(p.provider()) && p.available());
    }

    /** Label of the model that would serve a request now (the user's selection if set). */
    public String activeModelLabel() {
        if (modelPref.active() != null) {
            return modelPref.active();
        }
        return localRealProvider().map(LlmPort::modelLabel).orElse("local model");
    }

    private Optional<LlmPort> portFor(String provider) {
        if (provider == null) return Optional.empty();
        return ports.stream()
                .filter(p -> provider.equals(p.provider()))
                .filter(LlmPort::available)
                .findFirst();
    }

    /** A local, free real provider (Ollama) if reachable — the safe fallback (no spend). */
    private Optional<LlmPort> localRealProvider() {
        return portFor("ollama");
    }

    /**
     * Generate via the model's provider, tracing latency/provider/model. Remote providers are
     * budget-gated (falling back to local/stub when over cap) and their egress is audited.
     * Resilient: any provider error falls back to the offline stub so a feature never fails.
     */
    public LlmPort.LlmResult generate(LlmPort.LlmRequest request) {
        // Mark the feature so any model call on this thread exports to Langfuse named by it.
        tracer.setFeature(request.feature());
        try {
            return doGenerate(request);
        } finally {
            tracer.clearFeature();
        }
    }

    private LlmPort.LlmResult doGenerate(LlmPort.LlmRequest request) {
        String model = request.model();
        if (model == null || model.isBlank()) {
            model = modelPref.active();
        }
        Optional<LlmPort> chosen = portFor(providerForModel(model));

        // Remote + over budget → don't spend; use a local model if we have one.
        if (chosen.isPresent() && isRemote(chosen.get().provider())
                && !budget.canSpend(chosen.get().provider())) {
            log.warn("Provider '{}' over budget — falling back to local", chosen.get().provider());
            chosen = localRealProvider();
            model = null; // let the local provider pick its own model
        }
        // No provider for the model (e.g. remote key missing) → any local real provider.
        if (chosen.isEmpty()) {
            chosen = localRealProvider();
            model = null;
        }

        if (chosen.isPresent()) {
            LlmPort port = chosen.get();
            // Claude Code loads the user's skills itself; every other provider gets them injected,
            // so a local model has the same capabilities as the CLI rather than none.
            String system = "claude-code".equals(port.provider())
                    ? request.system()
                    : skills.applyTo(request.system());
            LlmPort.LlmRequest req = new LlmPort.LlmRequest(
                    request.feature(), system, request.prompt(), model);
            long t0 = System.currentTimeMillis();
            try {
                LlmPort.LlmResult result = port.generate(req);
                if (result.text() != null && !result.text().isBlank()) {
                    tracer.trace(request.feature(), result.provider(), result.model(),
                            System.currentTimeMillis() - t0, result.hypothesis());
                    if (isRemote(result.provider())) {
                        audit.record("llm-egress", result.provider(),
                                "feature=" + request.feature() + " model=" + result.model());
                        budget.record(result.provider(), estimateCents(request, result));
                    }
                    return result;
                }
            } catch (Exception e) {
                log.warn("LLM provider '{}' failed for feature '{}': {} — falling back to stub",
                        port.provider(), request.feature(), e.getMessage());
            }
        }

        long t0 = System.currentTimeMillis();
        LlmPort.LlmResult result = stub().generate(request);
        tracer.trace(request.feature(), result.provider(), result.model(),
                System.currentTimeMillis() - t0, result.hypothesis());
        return result;
    }

    /** Rough cost estimate (cents) to move the budget bar — not a billing figure. */
    private static int estimateCents(LlmPort.LlmRequest req, LlmPort.LlmResult res) {
        int chars = (req.prompt() == null ? 0 : req.prompt().length())
                + (res.text() == null ? 0 : res.text().length());
        return Math.max(1, chars / 2000); // ~1c per 2k chars, order-of-magnitude
    }

    private LlmPort stub() {
        return ports.stream().filter(p -> "stub".equals(p.provider())).findFirst().orElseThrow();
    }
}

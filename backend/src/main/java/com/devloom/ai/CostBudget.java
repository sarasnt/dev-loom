package com.devloom.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-provider cost budget (SPEC.md §20/NFR-7). Local providers (stub/ollama) are free and
 * uncapped; paid BYO providers (anthropic/openai) are capped, enforced pre-call. This is the
 * enforcement seam — real usage accrues once a paid adapter reports token cost. Kept in
 * memory for the MVP; move to the DB when persistence across restarts is needed.
 */
@Component
public class CostBudget {

    private final int anthropicCapCents;
    private final int openaiCapCents;
    private final Map<String, Integer> usedCents = new ConcurrentHashMap<>();

    public CostBudget(
            @Value("${devloom.ai.anthropic-cap-cents:2000}") int anthropicCapCents,
            @Value("${devloom.ai.openai-cap-cents:2000}") int openaiCapCents) {
        this.anthropicCapCents = anthropicCapCents;
        this.openaiCapCents = openaiCapCents;
    }

    public boolean isFree(String provider) {
        return provider == null || provider.equals("stub") || provider.equals("ollama");
    }

    public Integer capCents(String provider) {
        return switch (provider) {
            case "anthropic" -> anthropicCapCents;
            case "openai" -> openaiCapCents;
            default -> null; // local = uncapped
        };
    }

    public int usedCents(String provider) {
        return usedCents.getOrDefault(provider, 0);
    }

    /** True if a call to this provider is allowed under its cap (local is always allowed). */
    public boolean canSpend(String provider) {
        Integer cap = capCents(provider);
        return cap == null || usedCents(provider) < cap;
    }

    /** Record spend after a paid call. No-op for free providers. */
    public void record(String provider, int cents) {
        if (!isFree(provider)) {
            usedCents.merge(provider, cents, Integer::sum);
        }
    }
}

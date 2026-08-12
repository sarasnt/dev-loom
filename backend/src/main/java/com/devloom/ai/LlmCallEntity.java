package com.devloom.ai;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One observed model call. Holds measurements only — never prompt or response text — so the
 * monitoring history can be kept without becoming a transcript of everything you asked.
 */
@Entity
@Table(name = "llm_call")
public class LlmCallEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "at", nullable = false)
    private Instant at;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(nullable = false)
    private boolean ok;

    private String error;

    protected LlmCallEntity() {}

    public static LlmCallEntity of(Instant at, String provider, String model, long latencyMs,
                                   long inputTokens, long outputTokens, boolean ok, String error) {
        LlmCallEntity e = new LlmCallEntity();
        e.at = at;
        e.provider = provider;
        e.model = model;
        e.latencyMs = latencyMs;
        e.inputTokens = inputTokens;
        e.outputTokens = outputTokens;
        e.ok = ok;
        // Provider errors can be enormous (HTML error pages); the panel only shows a line of it.
        e.error = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        return e;
    }

    public Long getId() { return id; }
    public Instant getAt() { return at; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public long getLatencyMs() { return latencyMs; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public boolean isOk() { return ok; }
    public String getError() { return error; }
}

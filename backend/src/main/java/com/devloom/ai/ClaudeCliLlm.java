package com.devloom.ai;

import org.springframework.stereotype.Component;

/**
 * Claude Code via the user's subscription (docs/SPEC-sources.md §14), executed by the host
 * agent's logged-in {@code claude} CLI. Model id {@code claude-code}. Available only when the
 * agent is up and the CLI is logged in; no API key needed.
 */
@Component
public class ClaudeCliLlm implements LlmPort {

    public static final String MODEL = "claude-code";

    private final HostAgentClient agent;

    public ClaudeCliLlm(HostAgentClient agent) {
        this.agent = agent;
    }

    @Override
    public String provider() {
        return "claude-code";
    }

    @Override
    public boolean available() {
        return agent.claudeAvailable();
    }

    @Override
    public String modelLabel() {
        return MODEL;
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        HostAgentClient.Result r = agent.claude(request.system(), request.prompt());
        return new LlmResult(r.text(), MODEL, provider(), true);
    }
}

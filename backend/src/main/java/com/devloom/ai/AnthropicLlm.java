package com.devloom.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Anthropic Messages API adapter (SPEC.md §20) — a paid, remote provider. Active only when an
 * Anthropic key is configured (app-stored or env). Used for {@code claude-*} models; data
 * leaves the machine, so the {@link LlmRouter} gates egress and records cost. Generation runs
 * through LangChain4j so {@link ModelMonitor} observes latency + token usage.
 */
@Component
public class AnthropicLlm implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(AnthropicLlm.class);
    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    private final CredentialStore credentials;
    private final ModelMonitor monitor;
    private final ToolLoop toolLoop;
    private final Sampling sampling;
    private final String baseUrl;

    public AnthropicLlm(CredentialStore credentials, ModelMonitor monitor, ToolLoop toolLoop, Sampling sampling,
                        @Value("${devloom.ai.anthropic-base-url:https://api.anthropic.com}") String baseUrl) {
        this.credentials = credentials;
        this.monitor = monitor;
        this.toolLoop = toolLoop;
        this.sampling = sampling;
        // LangChain4j expects the versioned base (…/v1/); our config holds the host root.
        this.baseUrl = baseUrl.contains("/v1") ? baseUrl : (baseUrl.endsWith("/") ? baseUrl + "v1/" : baseUrl + "/v1/");
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    @Override
    public boolean available() {
        return credentials.hasKey("anthropic");
    }

    @Override
    public String modelLabel() {
        return DEFAULT_MODEL;
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        String key = credentials.key("anthropic").orElseThrow(() -> new IllegalStateException("no anthropic key"));
        String model = (request.model() == null || request.model().isBlank()) ? DEFAULT_MODEL : request.model();
        AnthropicChatModel chat = AnthropicChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .modelName(model)
                .maxTokens(1024)
                .timeout(Duration.ofSeconds(120))
                .listeners(List.of(monitor))
                // Sampling by feature: grounded work near-greedy, brainstorming warm. Left unset
                // this ran at the provider default, which made repeat runs disagree with themselves.
                .temperature(sampling.temperature(request.feature(), model))
                .topP(sampling.topP(request.feature(), model))
                .build();
        List<ChatMessage> messages = new ArrayList<>();
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(SystemMessage.from(request.system()));
        }
        messages.add(UserMessage.from(request.prompt()));
        // Through the tool loop, like the local adapter: a paid model that cannot read the repo
        // it was asked about is no more useful than a local one that cannot.
        // No context window to budget against: Anthropic's window is 200K tokens — far larger
        // than any tool-loop conversation here — and it fails loudly (an API error) on overflow
        // rather than Ollama's silent front-truncation, so clipping a tool result would only lose
        // it information for no safety benefit.
        ToolLoop.Reply reply = toolLoop.run(ToolLoop.blocking(chat), messages, request.repoPath(),
                request.repoWritable(), StreamSink.NONE, model, null);
        String text = reply.text() == null ? "" : reply.text();
        log.info("Anthropic generate: model={} chars={}", model, text.length());
        return new LlmResult(text, model, provider(), true, reply.telemetry());
    }
}

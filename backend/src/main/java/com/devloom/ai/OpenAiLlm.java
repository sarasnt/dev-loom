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
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * OpenAI Chat Completions adapter (SPEC.md §20) — a paid, remote provider. Active only when an
 * OpenAI key is configured. Used for {@code gpt-*}/{@code o*} models; egress is gated and cost
 * recorded by the {@link LlmRouter}. Generation runs through LangChain4j so {@link ModelMonitor}
 * observes latency + token usage.
 */
@Component
public class OpenAiLlm implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAiLlm.class);
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final CredentialStore credentials;
    private final ModelMonitor monitor;
    private final ToolLoop toolLoop;
    private final String baseUrl;

    public OpenAiLlm(CredentialStore credentials, ModelMonitor monitor, ToolLoop toolLoop,
                     @Value("${devloom.ai.openai-base-url:https://api.openai.com}") String baseUrl) {
        this.credentials = credentials;
        this.monitor = monitor;
        this.toolLoop = toolLoop;
        // LangChain4j expects the base ending at /v1; our config holds the host root.
        this.baseUrl = baseUrl.endsWith("/v1") ? baseUrl : baseUrl + "/v1";
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public boolean available() {
        return credentials.hasKey("openai");
    }

    @Override
    public String modelLabel() {
        return DEFAULT_MODEL;
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        String key = credentials.key("openai").orElseThrow(() -> new IllegalStateException("no openai key"));
        String model = (request.model() == null || request.model().isBlank()) ? DEFAULT_MODEL : request.model();
        OpenAiChatModel chat = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .modelName(model)
                .timeout(Duration.ofSeconds(120))
                .listeners(List.of(monitor))
                // Sampling by feature: grounded work near-greedy, brainstorming warm. Left unset
                // this ran at the provider default, which made repeat runs disagree with themselves.
                .temperature(Sampling.temperature(request.feature()))
                .topP(Sampling.topP(request.feature()))
                .build();
        List<ChatMessage> messages = new ArrayList<>();
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(SystemMessage.from(request.system()));
        }
        messages.add(UserMessage.from(request.prompt()));
        // Through the tool loop, like the local adapter: a paid model that cannot read the repo
        // it was asked about is no more useful than a local one that cannot.
        String answer = toolLoop.chat(chat, messages, request.repoPath());
        String text = answer == null ? "" : answer;
        log.info("OpenAI generate: model={} chars={}", model, text.length());
        return new LlmResult(text, model, provider(), true);
    }
}

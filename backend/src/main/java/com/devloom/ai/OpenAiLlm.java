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
    private final String baseUrl;

    public OpenAiLlm(CredentialStore credentials, ModelMonitor monitor,
                     @Value("${devloom.ai.openai-base-url:https://api.openai.com}") String baseUrl) {
        this.credentials = credentials;
        this.monitor = monitor;
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
                .build();
        List<ChatMessage> messages = new ArrayList<>();
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(SystemMessage.from(request.system()));
        }
        messages.add(UserMessage.from(request.prompt()));
        ChatResponse resp = chat.chat(ChatRequest.builder().messages(messages).build());
        String text = resp.aiMessage() == null || resp.aiMessage().text() == null ? "" : resp.aiMessage().text();
        log.info("OpenAI generate: model={} chars={}", model, text.length());
        return new LlmResult(text, model, provider(), true);
    }
}

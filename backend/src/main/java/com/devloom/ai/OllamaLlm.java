package com.devloom.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;

/**
 * Local model adapter via Ollama (SPEC.md §20). Local-first, free, nothing leaves the
 * machine. Activates only when an Ollama server is reachable — {@link #available()} pings
 * it with a short connect timeout so the app degrades to {@link StubLlm} instantly when
 * Ollama isn't running.
 */
@Component
public class OllamaLlm implements LlmPort {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlm.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final String defaultModel;
    private final String baseUrl;
    private final RestClient http;
    private final ModelMonitor monitor;
    private final ToolLoop toolLoop;
    private final Sampling sampling;

    public OllamaLlm(
            @Value("${devloom.ai.ollama-base-url:http://localhost:11434}") String baseUrl,
            @Value("${devloom.ai.default-model:Qwen3-Coder-30B-A3B}") String defaultModel,
            ModelMonitor monitor, ToolLoop toolLoop, Sampling sampling) {
        this.defaultModel = defaultModel;
        this.baseUrl = baseUrl;
        this.monitor = monitor;
        this.toolLoop = toolLoop;
        this.sampling = sampling;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1500);    // fail fast when Ollama isn't there
        f.setReadTimeout(120_000);    // generation can take a while
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override
    public String provider() {
        return "ollama";
    }

    @Override
    public boolean available() {
        // Reachable AND has at least one pulled model.
        return !models().isEmpty();
    }

    @Override
    public String modelLabel() {
        List<String> models = models();
        return models.isEmpty() ? defaultModel : resolveModel(null);
    }

    @Override
    public LlmResult generate(LlmRequest request) {
        return generate(request, StreamSink.NONE);
    }

    @Override
    public LlmResult generate(LlmRequest request, StreamSink sink) {
        String model = resolveModel(request.model());
        if (sink != StreamSink.NONE) return streaming(request, model, sink);
        // Route through LangChain4j so ModelMonitor (a ChatModelListener) observes the call.
        OllamaChatModel chat = OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
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
        // Through the tool loop so the model can actually call any MCP tools the user enabled;
        // with none enabled this is a plain one-shot chat.
        ToolLoop.Reply reply = toolLoop.run(ToolLoop.blocking(chat), messages, request.repoPath(),
                request.repoWritable(), StreamSink.NONE, model);
        String text = reply.text() == null ? "" : reply.text();
        log.info("Ollama generate: model={} chars={} {}", model, text.length(), reply.telemetry());
        return new LlmResult(text, model, provider(), true, reply.telemetry());
    }

    /**
     * The same turn, streamed. Local models are slow enough that watching the reply appear is the
     * difference between "working" and "hung" — a 30-second wait with no output looks like a
     * failure even when it isn't.
     *
     * <p>Each exchange still has to complete before the loop can act on it (a half-received tool
     * call can't be executed), so this streams within a turn and blocks between turns.
     */
    private LlmResult streaming(LlmRequest request, String model, StreamSink sink) {
        OllamaStreamingChatModel chat = OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .timeout(Duration.ofSeconds(120))
                .listeners(List.of(monitor))
                .temperature(sampling.temperature(request.feature(), model))
                .topP(sampling.topP(request.feature(), model))
                .build();

        ToolLoop.Turn turn = (messages, specs) -> {
            CompletableFuture<ChatResponse> done = new CompletableFuture<>();
            ChatRequest req = specs == null || specs.isEmpty()
                    ? ChatRequest.builder().messages(messages).build()
                    : ChatRequest.builder().messages(messages).toolSpecifications(specs).build();
            chat.chat(req, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partial) {
                    sink.delta(partial);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    done.complete(response);
                }

                @Override
                public void onError(Throwable error) {
                    done.completeExceptionally(error);
                }
            });
            try {
                return done.get(150, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted while streaming", e);
            } catch (TimeoutException e) {
                throw new RuntimeException("the model stopped responding", e);
            }
        };

        List<ChatMessage> messages = new ArrayList<>();
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(SystemMessage.from(request.system()));
        }
        messages.add(UserMessage.from(request.prompt()));
        ToolLoop.Reply reply = toolLoop.run(turn, messages, request.repoPath(), request.repoWritable(), sink, model);
        String text = reply.text() == null ? "" : reply.text();
        log.info("Ollama stream: model={} chars={} {}", model, text.length(), reply.telemetry());
        return new LlmResult(text, model, provider(), true, reply.telemetry());
    }

    /** Names of models pulled into this Ollama instance (empty if unreachable). */
    @SuppressWarnings("unchecked")
    public List<String> models() {
        try {
            Map<String, Object> tags = http.get().uri("/api/tags").retrieve().body(MAP);
            Object models = tags == null ? null : tags.get("models");
            if (models instanceof List<?> list) {
                return list.stream()
                        .map(m -> m instanceof Map ? String.valueOf(((Map<String, Object>) m).get("name")) : "")
                        .filter(n -> n != null && !n.isBlank())
                        .toList();
            }
        } catch (Exception ignore) {
            // unreachable → treated as no models available
        }
        return List.of();
    }

    /** Use the requested/default model if it's actually pulled; otherwise the first available. */
    private String resolveModel(String requested) {
        List<String> models = models();
        String preferred = (requested != null && !requested.isBlank()) ? requested : defaultModel;
        for (String m : models) {
            if (m.equalsIgnoreCase(preferred) || m.startsWith(preferred + ":")) {
                return m;
            }
        }
        return models.isEmpty() ? preferred : models.getFirst();
    }
}

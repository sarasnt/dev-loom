package com.devloom.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.devloom.ai.OllamaAdminService;

/** Local model management (Ollama): list installed, pull (streaming progress), remove. */
@RestController
@RequestMapping("/api/v1/models")
public class ModelsController {

    private static final Logger log = LoggerFactory.getLogger(ModelsController.class);
    private final OllamaAdminService ollama;
    private final com.devloom.ai.ModelSettings modelSettings;
    private final ExecutorService sse = Executors.newCachedThreadPool();

    public ModelsController(OllamaAdminService ollama, com.devloom.ai.ModelSettings modelSettings) {
        this.ollama = ollama;
        this.modelSettings = modelSettings;
    }

    /**
     * Per-model advanced overrides, all in one response: the UI needs to mark which rows are
     * customised, and one request beats one per installed model.
     */
    @GetMapping("/advanced")
    public Map<String, Object> advanced() {
        Map<String, Object> byModel = new java.util.LinkedHashMap<>();
        modelSettings.all().forEach((name, a) -> byModel.put(name, a.toMap()));
        return Map.of("models", byModel);
    }

    public record AdvancedBody(String model, String groundedTemperature, String groundedTopP,
                               String creativeTemperature, String maxSteps) {}

    /** Save one model's overrides. Blank fields fall back to the global setting, not to zero. */
    @org.springframework.web.bind.annotation.PutMapping("/advanced")
    public Map<String, Object> setAdvanced(@org.springframework.web.bind.annotation.RequestBody AdvancedBody body) {
        if (body == null || body.model() == null || body.model().isBlank()) return advanced();
        modelSettings.save(body.model(), new com.devloom.ai.ModelSettings.Advanced(
                body.groundedTemperature(), body.groundedTopP(),
                body.creativeTemperature(), body.maxSteps()));
        return advanced();
    }

    @GetMapping("/installed")
    public List<Map<String, Object>> installed() {
        return ollama.installed();
    }

    /** Stream `ollama pull` progress: a {@code progress} event per line, then {@code done}/{@code error}. */
    @GetMapping("/pull/stream")
    public SseEmitter pull(@RequestParam String name) {
        SseEmitter emitter = new SseEmitter(3_600_000L);
        sse.execute(() -> {
            try {
                ollama.pull(name, line -> {
                    try {
                        emitter.send(SseEmitter.event().name("progress").data(line));
                    } catch (Exception ignore) { /* client gone */ }
                });
                emitter.send(SseEmitter.event().name("done").data(Map.of("name", name)));
                emitter.complete();
            } catch (Exception e) {
                log.warn("Model pull '{}' failed: {}", name, e.getMessage());
                try { emitter.send(SseEmitter.event().name("error").data(Map.of("error", String.valueOf(e.getMessage())))); } catch (Exception ignore) {}
                emitter.complete();
            }
        });
        return emitter;
    }

    /** Remove a model (name may contain a colon, e.g. gpt-oss:20b). */
    @DeleteMapping("/{name}")
    public Map<String, Object> remove(@PathVariable String name) {
        // Its overrides go with it — otherwise re-pulling the model silently resurrects settings
        // the user tuned for a version they deleted.
        modelSettings.save(name, com.devloom.ai.ModelSettings.NONE);
        return Map.of("removed", ollama.delete(name));
    }
}

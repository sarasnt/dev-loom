package com.devloom.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.devloom.common.AppConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Per-model overrides of the advanced knobs.
 *
 * <p>The global settings are one set of numbers for every model, which is the wrong shape once you
 * can install your own: qwen2.5-coder:7b and gpt-oss:20b do not want the same temperature, and a
 * model that wanders wants a smaller step budget than one that reads a repo methodically. Tuning
 * for one of them detunes the other.
 *
 * <p>So each knob resolves in three steps — this model's override, then the global setting, then
 * the shipped default — and every level stays visible in the UI as the placeholder for the level
 * below. Blank means "follow the level above", never "0", so raising a default later still reaches
 * a model whose box was never touched.
 *
 * <p>Stored as one JSON row per model rather than four keys each, because the key column is short
 * and a model name is already most of it.
 */
@Component
public class ModelSettings {

    private static final Logger log = LoggerFactory.getLogger(ModelSettings.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** All-blank: every knob follows the global setting. */
    public static final Advanced NONE = new Advanced("", "", "", "");

    /** Strings, not numbers, because "" is a meaningful value here and 0.0 is a different one. */
    public record Advanced(String groundedTemperature, String groundedTopP,
                           String creativeTemperature, String maxSteps) {

        public Advanced {
            groundedTemperature = blank(groundedTemperature);
            groundedTopP = blank(groundedTopP);
            creativeTemperature = blank(creativeTemperature);
            maxSteps = blank(maxSteps);
        }

        private static String blank(String s) {
            return s == null ? "" : s.trim();
        }

        public boolean isEmpty() {
            return groundedTemperature.isEmpty() && groundedTopP.isEmpty()
                    && creativeTemperature.isEmpty() && maxSteps.isEmpty();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("groundedTemperature", groundedTemperature);
            m.put("groundedTopP", groundedTopP);
            m.put("creativeTemperature", creativeTemperature);
            m.put("maxSteps", maxSteps);
            return m;
        }
    }

    private final AppConfigService config;
    // Read on every model call, so it is not worth a database round trip each time. Writes all go
    // through save(), which is the only thing that can invalidate it.
    private final Map<String, Advanced> cache = new ConcurrentHashMap<>();

    public ModelSettings(AppConfigService config) {
        this.config = config;
    }

    /** This model's overrides — all-blank when it has none, and for an unnamed model. */
    public Advanced get(String model) {
        if (model == null || model.isBlank()) return NONE;
        return cache.computeIfAbsent(model.trim(), m ->
                config.get(AppConfigService.MODEL_ADVANCED_PREFIX + m).map(ModelSettings::parse).orElse(NONE));
    }

    /** Save (or, when every field is blank, clear) one model's overrides. */
    public Advanced save(String model, Advanced a) {
        if (model == null || model.isBlank()) return NONE;
        String name = model.trim();
        String key = AppConfigService.MODEL_ADVANCED_PREFIX + name;
        try {
            config.set(key, a == null || a.isEmpty() ? null : JSON.writeValueAsString(a.toMap()));
        } catch (Exception e) {
            log.warn("Could not save advanced settings for {}: {}", name, e.toString());
        }
        cache.remove(name);
        return get(name);
    }

    /** Every model that has overrides, for showing which rows are customised without N requests. */
    public Map<String, Advanced> all() {
        Map<String, Advanced> m = new LinkedHashMap<>();
        config.byPrefix(AppConfigService.MODEL_ADVANCED_PREFIX)
                .forEach((name, json) -> m.put(name, parse(json)));
        return m;
    }

    private static Advanced parse(String json) {
        try {
            var n = JSON.readTree(json);
            return new Advanced(n.path("groundedTemperature").asText(""),
                    n.path("groundedTopP").asText(""),
                    n.path("creativeTemperature").asText(""),
                    n.path("maxSteps").asText(""));
        } catch (Exception e) {
            return NONE;   // a corrupt row must not change how a model samples
        }
    }
}

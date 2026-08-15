package com.devloom.ai;

import org.springframework.stereotype.Component;

import com.devloom.common.AppConfigService;

/**
 * Sampling settings per feature.
 *
 * <p>Every adapter used to build its model with no sampling parameters at all, so each ran at its
 * provider's default — 0.8 for Ollama. That is a sensible default for open-ended chat and the
 * wrong one for the work most of this app does: asked the same grounded question about the same
 * repository, a model would read the files and answer correctly on one run and wander off into a
 * directory listing on the next.
 *
 * <p>The split by feature is the point, and is why these are two bands rather than one number.
 * Grounded work — repo analysis, build-failure diagnosis, judging — samples near-greedily, because
 * there is one right answer and creativity is a defect. Brainstorming stays warm, because there
 * the variety IS the feature and the same question should not produce the same three ideas every
 * time. A single global temperature would have to be wrong for one of them.
 *
 * <p>Both bands are adjustable (Settings › Models › Advanced) because the right values depend on
 * the model: these defaults were tuned against qwen3-coder and gpt-oss, and someone running a
 * different model shouldn't have to edit Java to try 0.3. Adjustable per model too — see
 * {@link ModelSettings} for why one global number stops being enough once you can install your own.
 */
@Component
public class Sampling {

    /** Tuned for grounded work: one right answer, so reproducibility beats variety. */
    public static final double GROUNDED_TEMPERATURE = 0.1;
    public static final double GROUNDED_TOP_P = 0.9;
    /** Tuned for brainstorming, where repeating yesterday's three ideas is the failure. */
    public static final double CREATIVE_TEMPERATURE = 0.7;

    private final AppConfigService config;
    private final ModelSettings perModel;

    public Sampling(AppConfigService config, ModelSettings perModel) {
        this.config = config;
        this.perModel = perModel;
    }

    /** Features where there is a right answer, and the model's job is to find it rather than riff. */
    private static boolean grounded(String feature) {
        return "fleet".equals(feature) || "build-failure".equals(feature) || "judge".equals(feature);
    }

    /**
     * Temperature for a feature on a model, or null to leave the provider's default alone.
     *
     * @param model the resolved model name — its own setting wins over the global one
     */
    public Double temperature(String feature, String model) {
        if (feature == null) return null;
        ModelSettings.Advanced m = perModel.get(model);
        if (grounded(feature)) {
            return num(m.groundedTemperature(), AppConfigService.SAMPLING_GROUNDED_TEMP, GROUNDED_TEMPERATURE);
        }
        if ("brainstorm".equals(feature)) {
            return num(m.creativeTemperature(), AppConfigService.SAMPLING_CREATIVE_TEMP, CREATIVE_TEMPERATURE);
        }
        return null;
    }

    /**
     * Top-p for a feature. Paired with a low temperature it trims the long tail that produces the
     * occasional wild step — a tool call for something that was never mentioned.
     */
    public Double topP(String feature, String model) {
        if (feature == null || !grounded(feature)) return null;
        return num(perModel.get(model).groundedTopP(), AppConfigService.SAMPLING_GROUNDED_TOP_P, GROUNDED_TOP_P);
    }

    /**
     * A fixed seed for grounded features, or null to leave the sampler random. Off by default:
     * re-running a failed analysis is partly a way to harvest variance, and a pinned seed would
     * make every re-run fail identically. Never applies to brainstorm — a seeded brainstorm
     * produces the same three ideas every time, which is the failure the creative band exists
     * to avoid.
     */
    public Integer seed(String feature) {
        if (feature == null || !grounded(feature)) return null;
        return config.get(AppConfigService.SAMPLING_SEED).map(v -> {
            try {
                int n = Integer.parseInt(v.trim());
                return n >= 0 ? n : null;   // a negative seed is a typo, not a request
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }

    /**
     * This model's override, else the global setting, else what ships. Anything unparseable or
     * outside [0,1] falls through to the next level rather than being clamped: a temperature of 5
     * in the box is a mistake, and silently running at 1.0 would hide it.
     */
    private double num(String override, String key, double shipped) {
        Double v = parse(override);
        if (v != null) return v;
        Double global = config.get(key).map(Sampling::parse).orElse(null);
        return global != null ? global : shipped;
    }

    private static Double parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            double d = Double.parseDouble(s.trim());
            return d >= 0.0 && d <= 1.0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

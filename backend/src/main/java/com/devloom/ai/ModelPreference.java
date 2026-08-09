package com.devloom.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The user's currently selected local model (SPEC.md §20). Single-user, in-memory — the
 * rail's model dropdown sets it and every AI feature honors it unless the call explicitly
 * overrides the model. Empty means "let the provider auto-pick a pulled model".
 */
@Component
public class ModelPreference {

    private volatile String active;

    public ModelPreference(@Value("${devloom.ai.default-model:}") String defaultModel) {
        this.active = (defaultModel == null || defaultModel.isBlank()) ? null : defaultModel;
    }

    /** The selected model, or null to let the provider auto-pick. */
    public String active() {
        return active;
    }

    public void set(String model) {
        this.active = (model == null || model.isBlank()) ? null : model.strip();
    }
}

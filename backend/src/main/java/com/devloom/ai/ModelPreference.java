package com.devloom.ai;

import org.springframework.stereotype.Component;

/**
 * The user's currently selected local model (SPEC.md §20). Single-user, in-memory. Now that
 * model choice is per-screen (passed per request), this is only a fallback. It starts EMPTY so
 * the active label auto-picks an actually-pulled model — seeding it from a configured default
 * that isn't pulled produced a phantom "active" model that wasn't in any dropdown.
 */
@Component
public class ModelPreference {

    private volatile String active;

    /** The selected model, or null to let the provider auto-pick. */
    public String active() {
        return active;
    }

    public void set(String model) {
        this.active = (model == null || model.isBlank()) ? null : model.strip();
    }
}

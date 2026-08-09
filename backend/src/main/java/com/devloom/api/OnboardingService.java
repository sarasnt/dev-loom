package com.devloom.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.devloom.ai.OllamaLlm;
import com.devloom.integrations.SourceCredentialStore;
import com.devloom.integrations.SourceInstanceRepository;

/**
 * Onboarding steps reflecting REAL state (SPEC.md §onboarding): which sources are connected,
 * whether a local model is available — so the first-run checklist is accurate, not fixed.
 */
@Service
public class OnboardingService {

    private final SourceInstanceRepository instances;
    private final SourceCredentialStore credentials;
    private final OllamaLlm ollama;

    public OnboardingService(SourceInstanceRepository instances, SourceCredentialStore credentials,
                             OllamaLlm ollama) {
        this.instances = instances;
        this.credentials = credentials;
        this.ollama = ollama;
    }

    public List<Dto.OnboardStep> steps() {
        List<Dto.OnboardStep> steps = new ArrayList<>();
        steps.add(new Dto.OnboardStep("✓", "Sign in", "single-user, on this machine", "done", null));

        steps.add(sourceStep("2", "Connect GitHub", "github", "GitHub App / token · read-only"));
        steps.add(sourceStep("3", "Connect Jira", "jira", "Cloud or on-prem · token/PAT"));
        steps.add(sourceStep("4", "Connect calendar", "calendar", "iCal feed"));

        boolean model = ollama.available();
        String modelName = model ? ollama.models().getFirst() : "none pulled";
        steps.add(new Dto.OnboardStep("5", "Choose a model",
                model ? "Local · " + modelName : "Pull a local model (Ollama) or add a key",
                model ? "done" : "now", model ? null : "Set up"));

        return steps;
    }

    private Dto.OnboardStep sourceStep(String n, String title, String type, String detail) {
        boolean connected = instances.findByType(type).stream()
                .anyMatch(i -> i.isEnabled() && credentials.hasCredential(i));
        return new Dto.OnboardStep(connected ? "✓" : n, title, detail,
                connected ? "done" : "now", connected ? null : "Connect");
    }
}

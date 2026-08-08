package com.devloom.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.devloom.ai.OllamaLlm;
import com.devloom.integrations.SourceConnector;

/**
 * Onboarding steps reflecting REAL state (SPEC.md §onboarding): which sources are connected,
 * whether a local model is available — so the first-run checklist is accurate, not fixed.
 */
@Service
public class OnboardingService {

    private final List<SourceConnector> connectors;
    private final OllamaLlm ollama;

    public OnboardingService(List<SourceConnector> connectors, OllamaLlm ollama) {
        this.connectors = connectors;
        this.ollama = ollama;
    }

    public List<Dto.OnboardStep> steps() {
        List<Dto.OnboardStep> steps = new ArrayList<>();
        steps.add(new Dto.OnboardStep("✓", "Sign in", "single-user, on this machine", "done", null));

        steps.add(sourceStep("2", "Connect GitHub", "GitHub", "GitHub App / token · read-only"));
        steps.add(sourceStep("3", "Connect Jira", "Jira", "on-prem Data Center · PAT"));
        steps.add(sourceStep("4", "Connect calendar", "Calendar", "Google iCal feed"));

        boolean model = ollama.available();
        String modelName = model ? ollama.models().getFirst() : "none pulled";
        steps.add(new Dto.OnboardStep("5", "Choose a model",
                model ? "Local · " + modelName : "Pull a local model (Ollama) or add a key",
                model ? "done" : "now", model ? null : "Set up"));

        return steps;
    }

    private Dto.OnboardStep sourceStep(String n, String title, String source, String detail) {
        boolean connected = connectors.stream()
                .anyMatch(c -> c.source().equals(source) && c.enabled());
        return new Dto.OnboardStep(connected ? "✓" : n, title, detail,
                connected ? "done" : "now", connected ? null : "Connect");
    }
}

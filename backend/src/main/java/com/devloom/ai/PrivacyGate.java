package com.devloom.ai;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * The single egress chokepoint (SPEC.md §20/§26). Decides whether a request touching a
 * given repo/project may leave the machine, and produces the egress-preview metadata the
 * UI shows before any remote AI action. Local-only repos can never egress — all-or-nothing.
 */
@Component
public class PrivacyGate {

    // In the MVP these come from config/DB; hard-coded here to match the fixtures.
    private static final Set<String> LOCAL_ONLY = Set.of("acme/secret-svc", "acme/payments");

    public boolean mayEgress(String repo) {
        return repo == null || !LOCAL_ONLY.contains(repo);
    }

    /** Boundary for a request, given the target provider and the repos in context. */
    public Boundary boundaryFor(String provider, Set<String> repos) {
        boolean anyLocalOnly = repos.stream().anyMatch(LOCAL_ONLY::contains);
        boolean remote = provider != null && !provider.equals("stub") && !provider.equals("ollama");
        if (anyLocalOnly || !remote) {
            return new Boundary("local", "On your machine");
        }
        return new Boundary("remote", "Leaves for " + provider);
    }

    public record Boundary(String mode, String label) {}
}

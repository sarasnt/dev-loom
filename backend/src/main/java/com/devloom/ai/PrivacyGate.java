package com.devloom.ai;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single egress chokepoint (SPEC.md §20/§26). Decides whether a request touching a
 * given repo/project may leave the machine, and produces the egress-preview metadata the
 * UI shows before any remote AI action. Local-only repos can never egress — all-or-nothing.
 *
 * <p>The local-only set is configured via {@code devloom.privacy.local-only-repos} (a comma
 * list of {@code owner/repo}). It defaults to empty — you mark real repos as local-only;
 * nothing is assumed.
 */
@Component
public class PrivacyGate {

    private final Set<String> localOnly;

    public PrivacyGate(@Value("${devloom.privacy.local-only-repos:}") String configured) {
        this.localOnly = (configured == null || configured.isBlank())
                ? Set.of()
                : new LinkedHashSet<>(Arrays.stream(configured.split(","))
                        .map(String::trim).filter(s -> !s.isBlank()).toList());
    }

    public boolean mayEgress(String repo) {
        return repo == null || !localOnly.contains(repo);
    }

    /** Repos that may never leave the machine (for the Privacy screen). */
    public java.util.List<String> localOnlyRepos() {
        return localOnly.stream().sorted().toList();
    }

    /** Boundary for a request, given the target provider and the repos in context. */
    public Boundary boundaryFor(String provider, Set<String> repos) {
        boolean anyLocalOnly = repos.stream().anyMatch(localOnly::contains);
        boolean remote = provider != null && !provider.equals("stub") && !provider.equals("ollama");
        if (anyLocalOnly || !remote) {
            return new Boundary("local", "On your machine");
        }
        return new Boundary("remote", "Leaves for " + provider);
    }

    public record Boundary(String mode, String label) {}
}

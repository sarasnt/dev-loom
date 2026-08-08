package com.devloom.api;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.devloom.ai.LlmPort;
import com.devloom.ai.LlmRouter;
import com.devloom.integrations.GitHubBuildAnalyzer;
import com.devloom.workmodel.WorkItemEntity;
import com.devloom.workmodel.WorkItemRepository;

/**
 * Assembles the build-failure analysis (SPEC.md §23). The log collection, truncation, and
 * secret redaction are real (see FixtureData → SecretRedactor). The summary/hypotheses go
 * through the {@link LlmRouter}: when a real local model (Ollama) is reachable it produces
 * the summary; otherwise the curated deterministic summary is kept so the view stays useful
 * offline. Either way the summary is flagged as reasoning, never fact.
 */
@Service
public class BuildFailureService {

    private static final String SYSTEM = """
            You are a senior engineer triaging a CI failure. Given the failing test, the
            redacted log excerpt, and the suspect commit, write a 2-3 sentence summary of the
            most likely cause. Distinguish evidence from hypothesis. Do not invent details.""";

    private final FixtureData fixtures;
    private final LlmRouter llm;
    private final GitHubBuildAnalyzer ghAnalyzer;
    private final WorkItemRepository workItems;

    public BuildFailureService(FixtureData fixtures, LlmRouter llm,
                               GitHubBuildAnalyzer ghAnalyzer, WorkItemRepository workItems) {
        this.fixtures = fixtures;
        this.llm = llm;
        this.ghAnalyzer = ghAnalyzer;
        this.workItems = workItems;
    }

    public Dto.BuildFailure analyze(String id) {
        // A real GitHub Actions run id (numeric) → analyze the live run; else the sample build.
        if (id != null && id.matches("\\d{6,}") && ghAnalyzer.enabled()) {
            Optional<WorkItemEntity> item = workItems.findFirstByExtId(id);
            String repo = item.map(w -> metaRepo(w)).orElse(null);
            if (repo != null) {
                Dto.BuildFailure real = ghAnalyzer.analyze(repo, id);
                if (real != null) {
                    return real;
                }
            }
        }

        Dto.BuildFailure b = fixtures.buildFailure(id); // real redaction applied here

        if (!llm.hasRealModel()) {
            return b; // keep the curated summary offline
        }

        String logExcerpt = b.log().stream().map(Dto.LogLine::text).reduce("", (a, c) -> a + "\n" + c);
        String prompt = """
                Failing test: %s (job %s, step %s)
                Suspect commit range near: %s
                Redacted log excerpt:
                %s""".formatted(b.failingTest(), b.failingJob(), b.failingStep(), b.run(), logExcerpt);

        LlmPort.LlmResult r = llm.generate(new LlmPort.LlmRequest("build-failure", SYSTEM, prompt, null));

        // Only use the model's summary if a real model actually produced it; otherwise
        // (stub fallback) keep the curated one so the view stays useful offline.
        if ("stub".equals(r.provider()) || r.text() == null || r.text().isBlank()) {
            return b;
        }
        return new Dto.BuildFailure(
                b.id(), b.repo(), b.branch(), b.pr(), b.run(), b.failedAgo(), b.boundary(),
                r.text().trim(), "med", b.failingJob(), b.failingStep(), b.failingTest(),
                b.redacted(), b.log(), b.causes(), b.related(), b.diagnostics(), b.fixes());
    }

    /** Build items store meta as "branch,owner/repo" — recover the repo (2nd token). */
    private static String metaRepo(WorkItemEntity w) {
        String[] parts = w.getMetaCsv().split(",");
        return parts.length >= 2 ? parts[1].trim() : null;
    }
}


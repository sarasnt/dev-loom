package com.devloom.api;

import java.util.List;

import org.springframework.stereotype.Component;

import com.devloom.common.SecretRedactor;

/**
 * Canned data for the screens whose real backing (CI logs, agent handoff, provider config,
 * brainstorm sessions) is built out in Step 3 / needs live integrations. These are swap
 * points, not dead ends. The build-failure log is run through the real {@link SecretRedactor}
 * so redaction is genuine.
 */
@Component
public class FixtureData {

    private final SecretRedactor redactor;

    public FixtureData(SecretRedactor redactor) {
        this.redactor = redactor;
    }

    private Dto.EvidenceRef ev(String id) {
        return new Dto.EvidenceRef(id, null, "local");
    }

    public Dto.BuildFailure buildFailure(String id) {
        // Raw log with a real-looking secret; redaction is applied here, not faked.
        String rawSecretLine = "env: GITHUB_TOKEN=ghp_ABCDEF1234567890abcdefXYZ987654";
        String redactedSecretLine = redactor.redact(rawSecretLine);

        List<Dto.LogLine> log = List.of(
                new Dto.LogLine("job: test → step: ./gradlew test", "omitted"),
                new Dto.LogLine("test: PricingServiceTest.appliesTierDiscount", null),
                new Dto.LogLine("expected: 90.00 but was: 100.00        ← first failure", "fail"),
                new Dto.LogLine("  at PricingServiceTest.appliesTierDiscount(:211)", null),
                new Dto.LogLine("[… 340 lines omitted …]", "omitted"),
                new Dto.LogLine(redactedSecretLine, "redacted"));

        return new Dto.BuildFailure(
                id, "acme/billing", "feature/pricing", "482", "#" + id, "failed 22m ago",
                new Dto.Boundary("local", "On your machine"),
                "The test job failed on one assertion in PricingServiceTest. Your commit d4e5f6 is the only change touching that path — most likely a tier-boundary off-by-one.",
                "high", "test", "./gradlew test", "PricingServiceTest.appliesTierDiscount", true, log,
                List.of(
                        new Dto.Hypothesis(1, "Tier-boundary off-by-one in discount()", "high",
                                List.of(ev("assert L211"), ev("commit d4e5f6")), false),
                        new Dto.Hypothesis(2, "Stale test fixture", "low", List.of(), true)),
                List.of(ev("PR #482"), ev("commit d4e5f6"), ev("prior fail #1120"), ev("TICKET-91")),
                List.of("Run only the failing test locally.", "Inspect the tier boundary at qty=10."),
                List.of("Adjust the boundary check in discount() so qty=10 falls in the discounted tier."));
    }

    public Dto.Handoff handoff(String id) {
        String rendered = """
                # Agent Handoff — Fix failing CI on feature/pricing
                Repo: acme/billing @ feature/pricing  PR #482  Commits a1b2c3..d4e5f6
                Failing: `test` → PricingServiceTest.appliesTierDiscount

                ## Reproduce
                1. ./gradlew test --tests PricingServiceTest.appliesTierDiscount

                ## Evidence
                - expected 90.00 but was 100.00 (log L211)
                - commit d4e5f6 changed discount() — only change on this path

                ## Ranked hypotheses
                1. (high) tier-boundary off-by-one in discount()
                2. (low)  stale fixture — no evidence in diff

                ## Constraints & acceptance
                - Fix must make the failing test pass without weakening assertions.
                - Expected output: a diff + a passing test run.""";
        return new Dto.Handoff(id, "Fix CI on feature/pricing", "Claude Code", 1,
                new Dto.Boundary("local", "On your machine"), rendered,
                new Dto.Safety(
                        List.of("Verify the fix with tests before claiming done",
                                "Allowed: read repo, ./gradlew test, edit src/tests"),
                        List.of("Do NOT push / merge / deploy / delete without approval",
                                "Forbidden: network, deploy, destructive git")),
                List.of(ev("#482"), ev("d4e5f6"), ev("log (redacted)"), ev("TICKET-91")));
    }

    public List<Dto.Integration> integrations() {
        return List.of(
                new Dto.Integration("github", "GitHub", "connected",
                        "GitHub App · 6 repos · connected · 2m ago",
                        List.of("Contents", "Pull requests", "Issues", "Checks", "Actions", "Deployments"),
                        null, List.of("Manage repos", "Re-sync", "Disconnect")),
                new Dto.Integration("jira", "Jira", "partial", "partial — rate limited", null,
                        "Last sync hit an Atlassian rate limit. Retrying automatically in ~3m.",
                        List.of("Retry now", "Details")),
                new Dto.Integration("gcal", "Google Calendar", "connected", "connected · read · 5m ago",
                        null, null, List.of("Re-sync", "Disconnect")),
                new Dto.Integration("mscal", "Microsoft Calendar", "not_connected", null, null, null,
                        List.of("Connect")));
    }

    public Dto.Providers providers() {
        return new Dto.Providers(
                new Dto.LocalProvider("Ollama", "Qwen3-Coder-30B-A3B",
                        List.of("Qwen3-Coder-30B-A3B", "gpt-oss-20b", "Gemma 3 12B", "Mistral Small 3.2 24B"), true),
                new Dto.KeyProvider("Anthropic", "leaves for Anthropic", true, true, 2000, 640, null),
                new Dto.KeyProvider("OpenAI", "leaves for OpenAI", false, null, null, null,
                        "Uses the Responses API · stateless"),
                false);
    }

    public Dto.Privacy privacy() {
        return new Dto.Privacy(
                "Local-first — nothing leaves unless you add a key and approve it.",
                List.of("acme/secret-svc", "acme/payments"),
                List.of(new Dto.EgressEntry("09:02", "build #1893 summary", "Anthropic", "~2.1k tok"),
                        new Dto.EgressEntry("Mon", "(nothing left)", "", "")));
    }

    public Dto.Brainstorm brainstorm() {
        var sources = List.of(ev("PR #482"), ev("TICKET-91"), ev("build #1893"));
        var active = new Dto.BrainstormSession("s1", "Pricing refactor", "personal", "Qwen3-Coder",
                new Dto.Boundary("local", "all local"), sources,
                List.of(
                        new Dto.BrainstormMessage("you", "Should discount() be tier-aware or flat?", null, null, null),
                        new Dto.BrainstormMessage("ai",
                                "Given TICKET-91 and the failing test at the tier boundary, tier-aware is safer — the 500 came from a flat calc ignoring the qty=10 threshold. Keep it tier-aware and add a boundary test.",
                                "Qwen3-Coder", true,
                                List.of(ev("TICKET-91"), ev("build #1893")))));
        return new Dto.Brainstorm(
                List.of(new Dto.SessionRef("s1", "Pricing refactor"),
                        new Dto.SessionRef("s2", "Release plan"),
                        new Dto.SessionRef("s3", "Idempotency notes")),
                active);
    }

    public List<Dto.OnboardStep> onboarding() {
        return List.of(
                new Dto.OnboardStep("✓", "Sign in", "via GitHub · sara.santos", "done", null),
                new Dto.OnboardStep("✓", "Connect GitHub", "GitHub App · 6 repos · read-only", "done", null),
                new Dto.OnboardStep("3", "Connect Jira & calendars", "Jira · Google Calendar · Microsoft Calendar", "now", "Connect"),
                new Dto.OnboardStep("4", "Choose a model", "Local & free via Ollama — or add a key later", "todo", null),
                new Dto.OnboardStep("5", "Mark local-only repos", "These never leave your machine", "todo", null));
    }
}

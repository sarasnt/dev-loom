package com.devloom.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Judges whether a run did the thing it was asked to do.
 *
 * <p>{@link RunQuality} scores process — repeated calls, invented tools, burnt step budgets — and
 * deliberately never judges the answer, because at runtime there is no correct answer to compare
 * against. That left a real gap: a run told to review a pull request described the repository
 * instead, committed none of the process faults, and came back 0.75. Nothing in the system
 * disagreed with it.
 *
 * <p>So a second, cheap pass reads the task and the answer and rules on two things a reader could
 * check without knowing the right answer: did it attempt the thing that was asked, and is what it
 * says traceable to what it actually looked at. Both are weaker than correctness and neither is
 * free of the judge's own mistakes — it is a model, scoring a model. It earns its place by being
 * checked against {@code eval/}, where the right answers ARE known.
 */
@Service
public class AnswerJudge {

    private static final Logger log = LoggerFactory.getLogger(AnswerJudge.class);

    /**
     * Short and rubric-shaped on purpose. A long judging prompt makes a small model write an essay
     * about the answer rather than rule on it, and the essay is not what we store.
     */
    private static final String SYSTEM = """
            You are grading whether a piece of work did what it was asked to do. You are not
            redoing the work and you are not judging whether its conclusions are correct — only
            whether it attempted the request and stayed with what it actually looked at.

            Reply as JSON with exactly these fields, in this order:

            "why": one sentence, at most 20 words
            "didTask": "yes" | "partly" | "no"
            "grounded": true | false

            The reason comes first on purpose: decide what happened, then label it. Labelling
            first and explaining afterwards produced verdicts that contradicted their own reason.

            DID_TASK is "no" when the reply answers a different question, describes the material
            instead of doing the task, or asks what to do rather than doing it.

            Reporting that the task cannot be done, with a specific reason drawn from what was
            looked at, IS doing the task — that finding is the answer. Do not mark it "no" for
            refusing to invent one.

            A reply that does the work and then asks what to do next still did the task. Judge the
            work, not the sign-off — the trailing question is scored elsewhere and marking it "no"
            here punishes the same habit twice, which sent finished work back for a second attempt.

            GROUNDED is "no" only when the reply states specifics that nothing in WHAT IT
            ACTUALLY DID could have shown it. If it read the file it quotes, it is grounded.""";

    /** The name to edit the rubric under in Langfuse. */
    private static final String PROMPT = "devloom/answer-judge";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The verdict, as a shape instead of a hoped-for format. Property order is the point: "why"
     * comes first so the model still decides what happened before labelling it — under
     * constrained decoding, generation follows schema order, so this preserves the reason-first
     * rubric that fixed the self-contradicting verdicts.
     */
    private static final JsonSchema VERDICT_SCHEMA = JsonSchema.builder()
            .name("verdict")
            .rootElement(JsonObjectSchema.builder()
                    .addProperty("why", dev.langchain4j.model.chat.request.json.JsonStringSchema.builder()
                            .description("one sentence, at most 20 words").build())
                    .addProperty("didTask", JsonEnumSchema.builder()
                            .enumValues("yes", "partly", "no").build())
                    .addProperty("grounded", JsonBooleanSchema.builder().build())
                    .required("why", "didTask", "grounded")
                    .build())
            .build();

    private final LlmRouter llm;
    private final PromptLibrary prompts;
    private final com.devloom.common.AppConfigService config;

    public AnswerJudge(LlmRouter llm, PromptLibrary prompts,
                       com.devloom.common.AppConfigService config) {
        this.llm = llm;
        this.prompts = prompts;
        this.config = config;
        prompts.seed(PROMPT, SYSTEM);
    }

    /** Judging costs a second model call per run, so it can be turned off. On by default. */
    private boolean enabled() {
        return !config.get(com.devloom.common.AppConfigService.JUDGE_ENABLED)
                .map("false"::equalsIgnoreCase).orElse(false);
    }

    /**
     * @param adherence 1.0 did it, 0.5 partly, 0.0 didn't — null when no judgement could be made
     * @param grounded  whether the answer stays within what the run actually saw
     * @param note      the judge's one-line reason, for showing next to the score
     */
    public record Verdict(Double adherence, boolean grounded, String note) {}

    /** Judge one finished run. Never throws: a failed judgement is no judgement, not a failed run. */
    public Verdict judge(String task, String answer, String model, java.util.List<String> activity) {
        if (!enabled()) return null;
        if (task == null || task.isBlank() || answer == null || answer.isBlank()) return null;
        try {
            // What the run did is shown, not guessed at. Without it the judge had to infer whether
            // a file had been read, and inferred wrong in both directions — marking a correct
            // answer ungrounded because it couldn't see the read that produced it.
            String did = activity == null || activity.isEmpty()
                    ? "(it used no tools)"
                    : String.join("\n", activity.stream().map(a -> "- " + a).toList());
            String prompt = """
                    THE REQUEST:
                    %s

                    WHAT IT ACTUALLY DID:
                    %s

                    THE REPLY:
                    %s

                    Grade the reply.""".formatted(clip(task, 2_000), clip(did, 1_500), clip(answer, 6_000));

            LlmPort.LlmResult r = llm.generate(
                    new LlmPort.LlmRequest("judge", prompts.get(PROMPT, SYSTEM), prompt, model)
                            .withSchema(VERDICT_SCHEMA));
            String text = r.text() == null ? "" : r.text();

            Verdict v = parseJson(text);
            if (v == null) v = parseProse(text);   // remote adapters ignore the schema; keep them working
            if (v == null) {
                log.debug("Judge produced no usable verdict: {}", clip(text, 160));
                return null;
            }
            return v;
        } catch (Exception e) {
            log.debug("Answer judging skipped: {}", e.toString());
            return null;
        }
    }

    /** The schema-constrained path: shape guaranteed by decoding, so failure here means no JSON at all. */
    private static Verdict parseJson(String text) {
        try {
            JsonNode n = JSON.readTree(text.trim());
            Double adherence = switch (n.path("didTask").asText("")) {
                case "yes" -> 1.0;
                case "partly" -> 0.5;
                case "no" -> 0.0;
                default -> null;
            };
            if (adherence == null) return null;
            return new Verdict(adherence, n.path("grounded").asBoolean(true),
                    clip(n.path("why").asText(""), 160));
        } catch (Exception e) {
            return null;
        }
    }

    /** The prose fallback — the pre-schema format, still what remote models produce. */
    private static Verdict parseProse(String text) {
        Double adherence = switch (field(text, "DID_TASK")) {
            case "yes" -> 1.0;
            case "partly" -> 0.5;
            case "no" -> 0.0;
            default -> null;
        };
        if (adherence == null) return null;
        boolean grounded = !"no".equals(field(text, "GROUNDED"));
        return new Verdict(adherence, grounded, clip(field(text, "WHY"), 160));
    }

    /** The value after a "FIELD:" label, lowercased and trimmed of markdown emphasis. */
    private static String field(String text, String name) {
        Matcher m = Pattern.compile("(?im)^\\W*" + name + "\\s*:\\s*(.+)$").matcher(text);
        if (!m.find()) return "";
        return m.group(1).replaceAll("[*_`]", "").trim().toLowerCase();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}

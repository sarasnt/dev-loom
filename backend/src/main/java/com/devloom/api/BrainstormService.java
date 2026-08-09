package com.devloom.api;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.devloom.ai.LlmPort;
import com.devloom.ai.LlmRouter;
import com.devloom.workmodel.WorkItemRepository;

/**
 * Conversational brainstorming workspace (SPEC.md §Brainstorming). A genuine thinking
 * partner — not an answer machine. The system prompt encodes a structured brainstorming
 * method (understand intent → surface/challenge assumptions → options + a real
 * recommendation), in the spirit of the "superpowers" brainstorming discipline.
 *
 * <p>Multi-turn: the prior conversation is included so the model builds on it. Runs on the
 * local model via {@link LlmRouter} (nothing leaves the machine) with graceful stub fallback.
 * A dedicated brainstorm model can be configured (a stronger general model than the coder one)
 * without touching product code.
 */
@Service
public class BrainstormService {

    private static final String SYSTEM = """
            You are DevLoom's brainstorming partner — a sharp senior engineer who thinks WITH
            the user, not for them. Help them think; don't rush to an answer.

            Method:
            - Understand before solving. If the request is ambiguous or under-specified, ask
              ONE focused clarifying question first — the single most decision-relevant unknown.
              Don't interrogate; one good question at a time.
            - Separate the problem from the solution. Surface the real goal and constraints
              before proposing how.
            - Make assumptions explicit and challenge them, including your own. Play devil's
              advocate on weak ideas, respectfully.
            - When you propose, give 2–3 concrete options with honest trade-offs, then a clear
              recommendation and why. No fence-sitting.
            - Ground claims in the attached sources when relevant; distinguish what you know
              from what you're inferring.
            - Be concrete, technical, and tight. No filler, no restating the question.
            - Once the problem is clear, move to options + a recommendation rather than asking more.

            You are talking to an experienced engineer. Match that level.""";

    private static final int MAX_HISTORY_TURNS = 12;

    private static final int MAX_CONTEXT_SOURCES = 6;

    private final LlmRouter llm;
    private final WorkItemRepository workItems;
    private final String brainstormModel;

    public BrainstormService(LlmRouter llm, WorkItemRepository workItems,
                             @Value("${devloom.ai.brainstorm-model:}") String brainstormModel) {
        this.llm = llm;
        this.workItems = workItems;
        this.brainstormModel = brainstormModel == null || brainstormModel.isBlank() ? null : brainstormModel;
    }

    /**
     * A fresh brainstorm session — no canned conversation. The "in context" rail is seeded
     * with real work items (PRs, failed builds, tasks) so the user can ground the discussion
     * in their actual work. Runs local-first; the reply endpoint drives the real dialogue.
     */
    public Dto.Brainstorm initial() {
        List<Dto.EvidenceRef> inContext = workItems.findAllByOrderBySortOrderAsc().stream()
                .limit(MAX_CONTEXT_SOURCES)
                .map(w -> new Dto.EvidenceRef(w.getExtId(), w.getTitle(), "local"))
                .toList();

        Dto.BrainstormSession active = new Dto.BrainstormSession(
                "new", "New brainstorm", "personal", modelLabel(),
                new Dto.Boundary("local", "On your machine"),
                inContext, List.of());

        return new Dto.Brainstorm(List.of(new Dto.SessionRef("new", "New brainstorm")), active);
    }

    private String modelLabel() {
        String m = llm.activeModelLabel();
        return m == null || m.isBlank() ? "local model" : m;
    }

    public Dto.BrainstormMessage reply(Dto.BrainstormSend req) {
        List<String> ids = req.sourceIds() == null ? List.of() : req.sourceIds();
        List<Dto.Turn> history = req.history() == null ? List.of() : req.history();

        StringBuilder prompt = new StringBuilder();
        if (!ids.isEmpty()) {
            prompt.append("Attached sources for context: ").append(String.join(", ", ids)).append("\n\n");
        }
        if (!history.isEmpty()) {
            prompt.append("Conversation so far:\n");
            int start = Math.max(0, history.size() - MAX_HISTORY_TURNS);
            for (Dto.Turn t : history.subList(start, history.size())) {
                String who = "you".equalsIgnoreCase(t.role()) ? "User" : "Assistant";
                prompt.append(who).append(": ").append(t.text() == null ? "" : t.text()).append("\n");
            }
            prompt.append("\n");
        }
        prompt.append("User: ").append(req.message() == null ? "" : req.message()).append("\nAssistant:");

        LlmPort.LlmResult r = llm.generate(
                new LlmPort.LlmRequest("brainstorm", SYSTEM, prompt.toString(), brainstormModel));

        List<Dto.EvidenceRef> sources = ids.stream()
                .map(id -> new Dto.EvidenceRef(id, null, "local"))
                .toList();

        return new Dto.BrainstormMessage("ai", r.text(), r.model(), true, sources);
    }
}

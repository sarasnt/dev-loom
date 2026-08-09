package com.devloom.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.ai.LlmPort;
import com.devloom.ai.LlmRouter;
import com.devloom.brainstorm.BrainstormMessageEntity;
import com.devloom.brainstorm.BrainstormMessageRepository;
import com.devloom.brainstorm.BrainstormSessionEntity;
import com.devloom.brainstorm.BrainstormSessionRepository;
import com.devloom.workmodel.WorkItemEntity;
import com.devloom.workmodel.WorkItemRepository;

/**
 * Conversational brainstorming workspace (SPEC.md §Brainstorming) — a genuine thinking
 * partner, not an answer machine. Sessions and their messages are persisted, so history
 * survives refreshes/restarts and multiple sessions can be kept side by side.
 *
 * <p>The system prompt encodes a structured brainstorming method (understand intent →
 * surface/challenge assumptions → options + a real recommendation). Replies run on the
 * user's selected local model via {@link LlmRouter} (nothing leaves the machine) with a
 * graceful stub fallback. Multi-turn: prior messages in the session are the context.
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
    private final BrainstormSessionRepository sessions;
    private final BrainstormMessageRepository messages;
    private final WorkItemRepository workItems;

    public BrainstormService(LlmRouter llm, BrainstormSessionRepository sessions,
                             BrainstormMessageRepository messages, WorkItemRepository workItems) {
        this.llm = llm;
        this.sessions = sessions;
        this.messages = messages;
        this.workItems = workItems;
    }

    // ---- reads ----------------------------------------------------------------

    /** All sessions (most-recent first) + the active one (the most recent), with its messages. */
    @Transactional
    public Dto.Brainstorm overview() {
        List<BrainstormSessionEntity> all = sessions.findAllByOrderByUpdatedAtDesc();
        BrainstormSessionEntity active = all.isEmpty() ? sessions.save(BrainstormSessionEntity.create(null)) : all.getFirst();
        if (all.isEmpty()) {
            all = List.of(active);
        }
        List<Dto.SessionRef> refs = all.stream()
                .map(s -> new Dto.SessionRef(String.valueOf(s.getId()), s.getTitle()))
                .toList();
        return new Dto.Brainstorm(refs, toDto(active));
    }

    @Transactional
    public Dto.BrainstormSession session(String id) {
        return sessions.findById(parse(id)).map(this::toDto).orElseGet(() -> overview().active());
    }

    @Transactional
    public Dto.BrainstormSession createSession(String title) {
        return toDto(sessions.save(BrainstormSessionEntity.create(title)));
    }

    /** Delete a session and its messages. */
    @Transactional
    public void deleteSession(String id) {
        Long sid = parse(id);
        messages.deleteBySessionId(sid);
        sessions.deleteById(sid);
    }

    // ---- write: a turn --------------------------------------------------------

    @Transactional
    public Dto.BrainstormMessage reply(Dto.BrainstormSend req) {
        List<String> ids = req.sourceIds() == null ? List.of() : req.sourceIds();
        BrainstormSessionEntity session = sessions.findById(parse(req.sessionId()))
                .orElseGet(() -> sessions.save(BrainstormSessionEntity.create(null)));

        List<BrainstormMessageEntity> prior = messages.findBySessionIdOrderBySeqAsc(session.getId());
        int seq = prior.size();

        String userText = req.message() == null ? "" : req.message();
        messages.save(BrainstormMessageEntity.of(session.getId(), seq++, "you", userText, null, false));

        String prompt = buildPrompt(ids, prior, userText);
        LlmPort.LlmResult r = llm.generate(new LlmPort.LlmRequest("brainstorm", SYSTEM, prompt, null));

        messages.save(BrainstormMessageEntity.of(session.getId(), seq, "ai", r.text(), r.model(), true));

        // Title a fresh session from its first user message, so the list is readable.
        if (prior.isEmpty() && !userText.isBlank()) {
            session.setTitle(userText.length() > 48 ? userText.substring(0, 48) + "…" : userText);
        }
        session.touch();
        sessions.save(session);

        List<Dto.EvidenceRef> sources = ids.stream()
                .map(id -> new Dto.EvidenceRef(id, null, "local"))
                .toList();
        return new Dto.BrainstormMessage("ai", r.text(), r.model(), true, sources);
    }

    // ---- helpers --------------------------------------------------------------

    private String buildPrompt(List<String> ids, List<BrainstormMessageEntity> prior, String userText) {
        StringBuilder prompt = new StringBuilder();
        if (!ids.isEmpty()) {
            prompt.append("Attached sources for context: ").append(String.join(", ", ids)).append("\n\n");
        }
        if (!prior.isEmpty()) {
            prompt.append("Conversation so far:\n");
            int start = Math.max(0, prior.size() - MAX_HISTORY_TURNS);
            for (BrainstormMessageEntity m : prior.subList(start, prior.size())) {
                prompt.append("you".equalsIgnoreCase(m.getRole()) ? "User" : "Assistant")
                        .append(": ").append(m.getBody()).append("\n");
            }
            prompt.append("\n");
        }
        prompt.append("User: ").append(userText).append("\nAssistant:");
        return prompt.toString();
    }

    private Dto.BrainstormSession toDto(BrainstormSessionEntity s) {
        List<Dto.BrainstormMessage> msgs = messages.findBySessionIdOrderBySeqAsc(s.getId()).stream()
                .map(m -> new Dto.BrainstormMessage(m.getRole(), m.getBody(), m.getModel(),
                        m.isHypothesis(), List.of()))
                .toList();

        // Suggested sources to ground the discussion — the user's real work items.
        List<Dto.EvidenceRef> inContext = new ArrayList<>();
        for (WorkItemEntity w : workItems.findAllByOrderBySortOrderAsc()) {
            if (inContext.size() >= MAX_CONTEXT_SOURCES) break;
            inContext.add(new Dto.EvidenceRef(w.getExtId(), w.getTitle(), "local"));
        }

        return new Dto.BrainstormSession(
                String.valueOf(s.getId()), s.getTitle(), s.getVisibility(),
                llm.activeModelLabel(), new Dto.Boundary("local", "On your machine"),
                inContext, msgs);
    }

    private static Long parse(String id) {
        try {
            return Long.valueOf(id);
        } catch (Exception e) {
            return -1L;
        }
    }
}

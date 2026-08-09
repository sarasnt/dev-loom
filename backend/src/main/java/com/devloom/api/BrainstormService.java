package com.devloom.api;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.ai.LlmPort;
import com.devloom.ai.LlmRouter;
import com.devloom.brainstorm.BrainstormMessageEntity;
import com.devloom.brainstorm.BrainstormMessageRepository;
import com.devloom.brainstorm.BrainstormSessionEntity;
import com.devloom.brainstorm.BrainstormSessionRepository;

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

    private final LlmRouter llm;
    private final com.devloom.ai.HostAgentClient agent;
    private final BrainstormSessionRepository sessions;
    private final BrainstormMessageRepository messages;
    private final com.devloom.brainstorm.BrainstormContextRepository contexts;

    public BrainstormService(LlmRouter llm, com.devloom.ai.HostAgentClient agent,
                             BrainstormSessionRepository sessions,
                             BrainstormMessageRepository messages,
                             com.devloom.brainstorm.BrainstormContextRepository contexts) {
        this.llm = llm;
        this.agent = agent;
        this.sessions = sessions;
        this.messages = messages;
        this.contexts = contexts;
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
        return createSession(title, null);
    }

    /** Create a session, optionally bound to a local repo (Claude Code runs in its dir). */
    @Transactional
    public Dto.BrainstormSession createSession(String title, String repoPath) {
        BrainstormSessionEntity s = sessions.save(BrainstormSessionEntity.create(title, repoPath));
        if (s.getRepoPath() != null) {
            // The repo is the pinned, default context for a repo-bound session.
            String name = s.getRepoPath().replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1);
            contexts.save(com.devloom.brainstorm.BrainstormContextEntity.of(
                    s.getId(), "repo", s.getRepoPath(), name, true));
        }
        return toDto(s);
    }

    @Transactional
    public Dto.BrainstormSession addContext(String sessionId, Dto.ContextAdd body) {
        Long sid = parse(sessionId);
        contexts.save(com.devloom.brainstorm.BrainstormContextEntity.of(
                sid, body.kind() == null ? "note" : body.kind(), body.ref(),
                body.label() == null || body.label().isBlank() ? String.valueOf(body.ref()) : body.label(), false));
        return sessions.findById(sid).map(this::toDto).orElseGet(() -> overview().active());
    }

    @Transactional
    public Dto.BrainstormSession removeContext(String sessionId, String ctxId) {
        contexts.findById(parse(ctxId)).ifPresent(c -> {
            if (!c.isPinned() && c.getSessionId().equals(parse(sessionId))) contexts.deleteById(c.getId());
        });
        return sessions.findById(parse(sessionId)).map(this::toDto).orElseGet(() -> overview().active());
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
        BrainstormSessionEntity session = sessions.findById(parse(req.sessionId()))
                .orElseGet(() -> sessions.save(BrainstormSessionEntity.create(null)));

        // Context = the session's persisted items (repo is passed via cwd, not the prompt).
        List<String> ids = contexts.findBySessionIdOrderByIdAsc(session.getId()).stream()
                .filter(c -> !"repo".equals(c.getKind()))
                .map(c -> c.getLabel())
                .toList();

        List<BrainstormMessageEntity> prior = messages.findBySessionIdOrderBySeqAsc(session.getId());
        int seq = prior.size();

        String userText = req.message() == null ? "" : req.message();
        messages.save(BrainstormMessageEntity.of(session.getId(), seq++, "you", userText, null, false));

        String replyText;
        String replyModel;
        if (session.getRepoPath() != null) {
            // Repo-scoped: Claude Code runs in the repo dir (reads/iterates it, uses its skills),
            // resuming its own session for continuity — so we send just the new turn.
            String prompt = ids.isEmpty() ? userText
                    : "Attached sources: " + String.join(", ", ids) + "\n\n" + userText;
            try {
                com.devloom.ai.HostAgentClient.Result cr =
                        agent.claude(SYSTEM, prompt, session.getRepoPath(), session.getClaudeSessionId());
                replyText = cr.text();
                replyModel = "claude-code";
                if (cr.sessionId() != null) session.setClaudeSessionId(cr.sessionId());
            } catch (Exception e) {
                replyText = "Couldn't run Claude Code in " + session.getRepoPath()
                        + " — is the host agent running and `claude` logged in? (" + e.getMessage() + ")";
                replyModel = "claude-code";
            }
        } else {
            String prompt = buildPrompt(ids, prior, userText);
            LlmPort.LlmResult r = llm.generate(new LlmPort.LlmRequest("brainstorm", SYSTEM, prompt, null));
            replyText = r.text();
            replyModel = r.model();
        }

        messages.save(BrainstormMessageEntity.of(session.getId(), seq, "ai", replyText, replyModel, true));

        // Title a fresh session from its first user message, so the list is readable.
        if (prior.isEmpty() && !userText.isBlank()) {
            session.setTitle(userText.length() > 48 ? userText.substring(0, 48) + "…" : userText);
        }
        session.touch();
        sessions.save(session);

        List<Dto.EvidenceRef> sources = ids.stream()
                .map(id -> new Dto.EvidenceRef(id, null, "local"))
                .toList();
        return new Dto.BrainstormMessage("ai", replyText, replyModel, true, sources);
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

        List<Dto.ContextItem> inContext = contexts.findBySessionIdOrderByIdAsc(s.getId()).stream()
                .map(c -> new Dto.ContextItem(String.valueOf(c.getId()), c.getKind(), c.getRef(),
                        c.getLabel(), c.isPinned()))
                .toList();

        boolean repo = s.getRepoPath() != null;
        String model = repo ? "claude-code" : llm.activeModelLabel();
        return new Dto.BrainstormSession(
                String.valueOf(s.getId()), s.getTitle(), s.getVisibility(),
                model, new Dto.Boundary(repo ? "remote" : "local", repo ? "Claude Code in repo" : "On your machine"),
                inContext, msgs, s.getRepoPath());
    }

    private static Long parse(String id) {
        try {
            return Long.valueOf(id);
        } catch (Exception e) {
            return -1L;
        }
    }
}

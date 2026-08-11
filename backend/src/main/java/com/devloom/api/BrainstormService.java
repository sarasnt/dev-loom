package com.devloom.api;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devloom.ai.LlmPort;
import com.devloom.ai.LlmRouter;
import com.devloom.brainstorm.BrainstormContextEntity;
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
    private final com.devloom.workmodel.WorkItemRepository workItems;
    private final com.devloom.common.AppConfigService appConfig;
    private final com.devloom.repos.GitRepoRepository repos;

    public BrainstormService(LlmRouter llm, com.devloom.ai.HostAgentClient agent,
                             BrainstormSessionRepository sessions,
                             BrainstormMessageRepository messages,
                             com.devloom.brainstorm.BrainstormContextRepository contexts,
                             com.devloom.workmodel.WorkItemRepository workItems,
                             com.devloom.common.AppConfigService appConfig,
                             com.devloom.repos.GitRepoRepository repos) {
        this.llm = llm;
        this.agent = agent;
        this.sessions = sessions;
        this.messages = messages;
        this.contexts = contexts;
        this.workItems = workItems;
        this.appConfig = appConfig;
        this.repos = repos;
    }

    /** True if the session's repo is marked local-only (may only use local models). */
    private boolean repoLocalOnly(String repoPath) {
        return repoPath != null && repos.findByPath(repoPath).map(r -> r.isLocalOnly()).orElse(false);
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
                .map(s -> new Dto.SessionRef(String.valueOf(s.getId()), s.getTitle(), s.isCliMode()))
                .toList();
        return new Dto.Brainstorm(refs, toDto(active));
    }

    @Transactional
    public Dto.BrainstormSession session(String id) {
        return sessions.findById(parse(id)).map(this::toDto).orElseGet(() -> overview().active());
    }

    @Transactional
    public Dto.BrainstormSession createSession(String title) {
        return createSession(title, null, null);
    }

    public Dto.BrainstormSession createSession(String title, String repoPath) {
        return createSession(title, repoPath, null);
    }

    /**
     * Create a session, optionally bound to a local repo (Claude Code runs in its dir) and to an
     * initial model. Choosing {@code claude-cli} makes it a terminal session from the start (for
     * a repo, the terminal opens in that repo — cwd = repoPath).
     */
    @Transactional
    public Dto.BrainstormSession createSession(String title, String repoPath, String model) {
        BrainstormSessionEntity s = BrainstormSessionEntity.create(title, repoPath);
        if ("claude-cli".equals(model)) {
            s.setCliMode(true);
        } else if (model != null && !model.isBlank()) {
            s.setModel(model); // remember the chat model this session was created with
        }
        s = sessions.save(s);
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

    @Transactional
    public Dto.BrainstormSession renameSession(String id, String title) {
        BrainstormSessionEntity s = sessions.findById(parse(id)).orElseThrow();
        if (title != null && !title.isBlank()) {
            s.setTitle(title.strip());
            sessions.save(s);
        }
        return toDto(s);
    }

    /** All repo-bound sessions (for showing a repo's brainstorms in the Repos screen). */
    @Transactional
    public List<Dto.RepoSession> repoSessions() {
        return sessions.findByRepoPathIsNotNullOrderByUpdatedAtDesc().stream()
                .map(s -> new Dto.RepoSession(String.valueOf(s.getId()), s.getTitle(), s.getRepoPath()))
                .toList();
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

        // Context = the session's persisted items, expanded to full detail (repo via cwd, not prompt).
        String cblock = contextBlock(session.getId());
        // Labels only, for the evidence-ref chips on the reply.
        List<String> ids = contexts.findBySessionIdOrderByIdAsc(session.getId()).stream()
                .filter(c -> !"repo".equals(c.getKind()))
                .map(c -> c.getLabel())
                .toList();

        List<BrainstormMessageEntity> prior = messages.findBySessionIdOrderBySeqAsc(session.getId());
        int seq = prior.size();

        String userText = req.message() == null ? "" : req.message();
        messages.save(BrainstormMessageEntity.of(session.getId(), seq++, "you", userText, null, false));

        // Chat replies route through the router with the session's model (repo pinned as context).
        String prompt = buildPrompt(cblock, prior, userText);
        String model = req.model() != null ? req.model() : session.getModel();
        LlmPort.LlmResult r = llm.generate(new LlmPort.LlmRequest("brainstorm", SYSTEM, prompt, model));
        String replyText = r.text();
        String replyModel = r.model();

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

    /**
     * Streaming turn: emits reply text chunks via {@code onDelta} as they arrive and the final
     * (text, model) via {@code onDone}. Claude Code sessions (repo-bound, or when claude-code is
     * the selected model) stream token deltas from the agent; other models emit once.
     */
    @Transactional
    public void replyStreaming(Dto.BrainstormSend req,
                               java.util.function.Consumer<String> onDelta,
                               java.util.function.BiConsumer<String, String> onDone) {
        BrainstormSessionEntity session = sessions.findById(parse(req.sessionId()))
                .orElseGet(() -> sessions.save(BrainstormSessionEntity.create(null)));

        String cblock = contextBlock(session.getId());
        List<BrainstormMessageEntity> prior = messages.findBySessionIdOrderBySeqAsc(session.getId());
        int seq = prior.size();
        String userText = req.message() == null ? "" : req.message();
        messages.save(BrainstormMessageEntity.of(session.getId(), seq++, "you", userText, null, false));

        // Chat replies always go through the router with the session's chosen model (the old
        // programmatic claude-code agent path is retired; claude-cli terminals are separate).
        String reqModel = req.model();
        boolean useClaude = false;
        String finalText;
        String finalModel;

        if (useClaude) {
            String prompt = session.getRepoPath() != null
                    ? (cblock.isEmpty() ? userText : cblock + "\n" + userText)
                    : buildPrompt(cblock, prior, userText);
            StringBuilder acc = new StringBuilder();
            String[] result = {null};
            String[] sid = {null};
            boolean[] sawDelta = {false};
            try {
                agent.claudeStream(SYSTEM, prompt, session.getRepoPath(), session.getClaudeSessionId(),
                        req.model(), ev -> {
                    String type = str(ev, "type");
                    if ("stream_event".equals(type)) {
                        String t = deltaText(ev);
                        if (t != null && !t.isEmpty()) { sawDelta[0] = true; acc.append(t); onDelta.accept(t); }
                    } else if ("assistant".equals(type) && !sawDelta[0]) {
                        String t = assistantText(ev);
                        if (!t.isEmpty()) { acc.append(t); onDelta.accept(t); }
                    } else if ("result".equals(type)) {
                        if (ev.get("result") != null) result[0] = String.valueOf(ev.get("result"));
                        if (ev.get("session_id") != null) sid[0] = String.valueOf(ev.get("session_id"));
                    } else if ("system".equals(type) && sid[0] == null && ev.get("session_id") != null) {
                        sid[0] = String.valueOf(ev.get("session_id"));
                    }
                });
                finalText = result[0] != null ? result[0] : acc.toString();
                if (sid[0] != null) session.setClaudeSessionId(sid[0]);
            } catch (Exception e) {
                finalText = "Couldn't run Claude Code — is the host agent running and `claude` logged in? ("
                        + e.getMessage() + ")";
                onDelta.accept(finalText);
            }
            finalModel = "claude-code";
        } else {
            String chatModel = reqModel != null ? reqModel : session.getModel();
            LlmPort.LlmResult r = llm.generate(new LlmPort.LlmRequest("brainstorm", SYSTEM,
                    buildPrompt(cblock, prior, userText), chatModel));
            finalText = r.text();
            finalModel = r.model();
            onDelta.accept(finalText);
        }

        messages.save(BrainstormMessageEntity.of(session.getId(), seq, "ai", finalText, finalModel, true));
        if (prior.isEmpty() && !userText.isBlank()) {
            session.setTitle(userText.length() > 48 ? userText.substring(0, 48) + "…" : userText);
        }
        session.touch();
        sessions.save(session);
        onDone.accept(finalText, finalModel);
    }

    // ---- helpers --------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static String deltaText(Map<String, Object> ev) {
        Object event = ev.get("event");
        if (!(event instanceof Map<?, ?> em)) return null;
        if (!"content_block_delta".equals(String.valueOf(((Map<String, Object>) em).get("type")))) return null;
        Object delta = ((Map<String, Object>) em).get("delta");
        if (delta instanceof Map<?, ?> dm) {
            Object t = ((Map<String, Object>) dm).get("text");
            return t == null ? null : String.valueOf(t);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String assistantText(Map<String, Object> ev) {
        Object message = ev.get("message");
        if (!(message instanceof Map<?, ?> mm)) return "";
        Object content = ((Map<String, Object>) mm).get("content");
        StringBuilder sb = new StringBuilder();
        if (content instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> block && "text".equals(String.valueOf(((Map<String, Object>) block).get("type")))) {
                    Object t = ((Map<String, Object>) block).get("text");
                    if (t != null) sb.append(t);
                }
            }
        }
        return sb.toString();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * Expand a session's attached context into rich text the model can brainstorm about:
     * for a work item (Jira/build/PR/Notion) we inject its title, status, source, metadata
     * (incl. Jira custom fields) and description — not just its label. Repo context is handled
     * separately (via cwd), so it's excluded here.
     */
    private String contextBlock(Long sessionId) {
        List<BrainstormContextEntity> items = contexts.findBySessionIdOrderByIdAsc(sessionId);
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Attached context to brainstorm about:\n\n");
        for (BrainstormContextEntity c : items) {
            if ("repo".equals(c.getKind())) {
                sb.append("- Repo: ").append(c.getLabel())
                        .append(c.getRef() == null ? "" : " (" + c.getRef() + ")").append("\n\n");
            } else if ("workitem".equals(c.getKind()) && c.getRef() != null) {
                workItems.findFirstByExtId(c.getRef()).ifPresentOrElse(
                        w -> sb.append(renderWorkItem(w)),
                        () -> sb.append("- ").append(c.getLabel()).append("\n\n"));
            } else if ("file".equals(c.getKind())) {
                sb.append("- File: ").append(c.getRef() == null ? c.getLabel() : c.getRef()).append("\n\n");
            } else {
                sb.append("- Note: ").append(c.getLabel()).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String renderWorkItem(com.devloom.workmodel.WorkItemEntity w) {
        StringBuilder b = new StringBuilder();
        b.append("### ").append(w.getType()).append(" · ").append(w.getExtId())
                .append(" — ").append(w.getTitle()).append("\n");
        b.append("Source: ").append(w.getSource()).append(" · Status: ").append(w.getStatus()).append("\n");
        String meta = w.getMetadata();
        if (meta != null && !meta.isBlank()) b.append(meta.strip()).append("\n");
        else if (w.getMetaCsv() != null && !w.getMetaCsv().isBlank()) b.append("Fields: ").append(w.getMetaCsv()).append("\n");
        if (w.getDescription() != null && !w.getDescription().isBlank()) {
            b.append("Description:\n").append(w.getDescription().strip()).append("\n");
        }
        return b.append("\n").toString();
    }

    private String buildPrompt(String contextBlock, List<BrainstormMessageEntity> prior, String userText) {
        StringBuilder prompt = new StringBuilder();
        if (contextBlock != null && !contextBlock.isBlank()) {
            prompt.append(contextBlock).append("\n");
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
        boolean localOnly = repoLocalOnly(s.getRepoPath());
        String model = s.isCliMode() ? "claude-cli"
                : (s.getModel() != null && !s.getModel().isBlank() ? s.getModel() : llm.activeModelLabel());
        boolean remoteModel = s.isCliMode() || model.startsWith("claude") || model.startsWith("gpt-")
                || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4");
        return new Dto.BrainstormSession(
                String.valueOf(s.getId()), s.getTitle(), s.getVisibility(),
                model, new Dto.Boundary(remoteModel ? "remote" : "local",
                        remoteModel ? "Leaves your machine" : "On your machine"),
                inContext, msgs, s.getRepoPath(), s.isCliMode(), s.getClaudeSessionId(), localOnly);
    }

    /**
     * Descriptor for opening an interactive Claude Code terminal (claude-cli mode) on a session.
     * Reuses the session's stored Claude session id so the embedded terminal, the programmatic
     * claude-code chat, and a plain {@code claude --resume} in the user's own terminal are all
     * the same conversation. Mints + persists a fresh UUID the first time (so the terminal
     * starts with {@code --session-id}); later opens resume it ({@code --resume}).
     */
    @Transactional
    public Map<String, Object> terminalInfo(String id) {
        BrainstormSessionEntity session = sessions.findById(parse(id))
                .orElseThrow(() -> new IllegalArgumentException("no session " + id));
        String sid = session.getClaudeSessionId();
        boolean resume = sid != null && !sid.isBlank();
        if (!resume) {
            sid = java.util.UUID.randomUUID().toString();
            session.setClaudeSessionId(sid);
        }
        // A session with no chat history that opens a terminal is terminal-native: lock it to
        // claude-cli so it isn't flipped to a chat model (which would show an empty pane).
        if (!session.isCliMode() && messages.findBySessionIdOrderBySeqAsc(session.getId()).isEmpty()) {
            session.setCliMode(true);
        }
        sessions.save(session);
        // Repo sessions run in the repo; others use the configured default working dir (so
        // claude opens in a folder you trust once), falling back to the agent's home dir.
        String cwd = session.getRepoPath();
        if (cwd == null) cwd = appConfig.get(com.devloom.common.AppConfigService.TERMINAL_WORKDIR).orElse(null);

        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("cwd", cwd);   // null → agent opens in the user's home dir
        m.put("sessionId", sid);
        m.put("resume", resume);
        return m;
    }

    private static Long parse(String id) {
        try {
            return Long.valueOf(id);
        } catch (Exception e) {
            return -1L;
        }
    }
}

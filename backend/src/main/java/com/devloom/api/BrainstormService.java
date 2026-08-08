package com.devloom.api;

import java.util.List;

import org.springframework.stereotype.Service;

import com.devloom.ai.LlmPort;
import com.devloom.ai.LlmRouter;

/**
 * Conversational brainstorming workspace (SPEC.md §Brainstorming). A message plus the
 * explicitly-attached sources becomes a prompt routed through the {@link LlmRouter} — so it
 * runs on the local model (Ollama) when available, nothing leaving the machine, else the
 * offline stub. The reply is always flagged as reasoning and echoes back the sources it was
 * given (the "threads").
 */
@Service
public class BrainstormService {

    private static final String SYSTEM = """
            You are DevLoom's brainstorming partner for a software engineer. Be concise,
            concrete, and technical. Ground claims in the attached sources when relevant.
            Prefer offering a couple of options and a clear recommendation over a wall of text.""";

    private final LlmRouter llm;

    public BrainstormService(LlmRouter llm) {
        this.llm = llm;
    }

    public Dto.BrainstormMessage reply(Dto.BrainstormSend req) {
        List<String> ids = req.sourceIds() == null ? List.of() : req.sourceIds();
        String context = ids.isEmpty() ? "" : "Attached sources for context: " + String.join(", ", ids) + "\n\n";
        String prompt = context + "User: " + (req.message() == null ? "" : req.message());

        LlmPort.LlmResult r = llm.generate(new LlmPort.LlmRequest("brainstorm", SYSTEM, prompt, null));

        List<Dto.EvidenceRef> sources = ids.stream()
                .map(id -> new Dto.EvidenceRef(id, null, "local"))
                .toList();

        return new Dto.BrainstormMessage("ai", r.text(), r.model(), true, sources);
    }
}

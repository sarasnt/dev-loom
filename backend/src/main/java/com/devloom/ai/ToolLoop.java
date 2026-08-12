package com.devloom.ai;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Runs a model turn that may call MCP tools: ask the model, execute whatever tools it requests,
 * feed the results back, repeat until it answers in prose.
 *
 * <p>Without this a "tool" is just a description the model can talk about but never invoke — which
 * is why MCP used to be Claude-only here. Shared by the adapters so Ollama and OpenAI behave the
 * same way.
 */
@Service
public class ToolLoop {

    private static final Logger log = LoggerFactory.getLogger(ToolLoop.class);

    /** A model that keeps calling tools without concluding must still terminate. */
    private static final int MAX_STEPS = 6;

    private final McpTools mcp;

    public ToolLoop(McpTools mcp) {
        this.mcp = mcp;
    }

    /** Whether any tools are currently exposed to non-Claude models. */
    public boolean hasTools() {
        return !mcp.tools().isEmpty();
    }

    /**
     * Chat with tool support. {@code messages} is mutated as the exchange grows.
     *
     * @return the model's final text, or {@code null} if it never produced any
     */
    public String chat(ChatModel model, List<ChatMessage> messages) {
        List<McpTools.Tool> tools = mcp.tools();
        List<ToolSpecification> specs = tools.stream().map(McpTools.Tool::spec).toList();
        if (specs.isEmpty()) {
            ChatResponse resp = model.chat(ChatRequest.builder().messages(messages).build());
            return text(resp);
        }
        withToolGuidance(messages, tools);
        // Smaller models get stuck re-calling the same tool with the same arguments; remember what
        // we've already run so the loop can say "you have this already" instead of burning steps.
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        for (int step = 0; step < MAX_STEPS; step++) {
            ChatResponse resp;
            try {
                resp = model.chat(ChatRequest.builder().messages(messages).toolSpecifications(specs).build());
            } catch (RuntimeException e) {
                // Plenty of local models simply can't do tool calling. Rather than fail the turn,
                // drop the tools and let the model answer from context alone.
                if (step == 0) {
                    log.info("Model rejected tool specifications ({}) — retrying without tools", e.getMessage());
                    ChatResponse plain = model.chat(ChatRequest.builder().messages(messages).build());
                    return text(plain);
                }
                throw e;
            }
            AiMessage ai = resp.aiMessage();
            if (ai == null) return null;
            if (!ai.hasToolExecutionRequests()) {
                // Several local models (qwen2.5-coder, qwen3-coder…) print a tool call as text
                // instead of returning a structured one, depending on their Ollama template. Taking
                // them at their word is the difference between MCP working on local models and not.
                List<ToolExecutionRequest> parsed = TextToolCalls.parse(text(resp), tools);
                if (parsed.isEmpty()) return text(resp);
                messages.add(ai);
                for (ToolExecutionRequest req : parsed) {
                    String result = runTool(req, seen);
                    // Fed back as a plain message: a tool-result message without a matching
                    // structured call confuses some chat templates.
                    messages.add(dev.langchain4j.data.message.UserMessage.from(
                            "Result of " + req.name() + ":\n" + result));
                }
                continue;
            }

            messages.add(ai);
            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                messages.add(ToolExecutionResultMessage.from(req, runTool(req, seen)));
            }
        }
        // Out of steps. Answering without tools invites confabulation, so say plainly that the
        // budget ran out — a truthful "I couldn't finish" beats an invented answer.
        messages.add(dev.langchain4j.data.message.UserMessage.from(
                "You have used all available tool steps. Answer now using only the tool results above."
                        + " If they were not enough, say exactly what is missing — do not invent details."));
        ChatResponse last = model.chat(ChatRequest.builder().messages(messages).build());
        String out = text(last);
        log.info("Tool loop hit the {}-step cap", MAX_STEPS);
        return out;
    }

    /**
     * Spell out the tool rules in the system message. The schemas alone are enough for a frontier
     * model, but smaller local ones pick a plausible-sounding wrong tool, re-call it, or narrate a
     * result they never got. Naming the tools and stating the rules plainly fixes most of that.
     */
    private void withToolGuidance(List<ChatMessage> messages, List<McpTools.Tool> tools) {
        StringBuilder g = new StringBuilder("""

                ## Tools

                You can call these tools. Rules:
                - Use a tool only when it genuinely helps; otherwise just answer.
                - Use the exact tool name, and arguments matching its schema.
                - Call one tool at a time and wait for its result before deciding the next step.
                - Never invent or guess a tool's output — only use what it actually returned.
                - Don't repeat a call you've already made; you already have that result.
                - Once you can answer, stop calling tools and reply in prose.

                Available tools:
                """);
        for (McpTools.Tool t : tools) {
            g.append("- ").append(t.name());
            String d = t.spec().description();
            if (d != null && !d.isBlank()) g.append(" — ").append(d.split("\\R")[0]);
            g.append('\n');
        }
        String guidance = g.toString();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage sm) {
                // Fold into the existing system message: a second one confuses some chat templates.
                messages.set(i, SystemMessage.from(sm.text() + "\n" + guidance));
                return;
            }
        }
        messages.add(0, SystemMessage.from(guidance.trim()));
    }

    /** Execute one tool request, short-circuiting a repeat of something already run this turn. */
    private String runTool(ToolExecutionRequest req, java.util.Map<String, String> seen) {
        String signature = req.name() + "(" + (req.arguments() == null ? "" : req.arguments()) + ")";
        if (seen.containsKey(signature)) {
            log.info("MCP tool call repeated, short-circuited: {}", signature);
            return "You already called " + signature + " and it returned:\n" + seen.get(signature)
                    + "\n\nDo not call it again — use this result, or answer with what you have.";
        }
        String server = mcp.serverFor(req.name());
        String result = server == null
                ? "Tool error: no MCP server offers a tool named " + req.name()
                : mcp.call(server, req.name(), req.arguments());
        seen.put(signature, result);
        log.info("MCP tool call: {}/{} -> {} chars", server, req.name(), result.length());
        return result;
    }

    private static String text(ChatResponse resp) {
        return resp == null || resp.aiMessage() == null ? null : resp.aiMessage().text();
    }
}

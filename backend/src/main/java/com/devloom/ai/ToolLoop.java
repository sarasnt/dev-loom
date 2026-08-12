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

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final McpTools mcp;
    private final RepoTools repoTools;

    public ToolLoop(McpTools mcp, RepoTools repoTools) {
        this.mcp = mcp;
        this.repoTools = repoTools;
    }

    /** Whether any tools are currently exposed to non-Claude models. */
    public boolean hasTools() {
        return !mcp.tools().isEmpty();
    }

    /**
     * One exchange with the model. Abstracted so the loop doesn't care whether the adapter got the
     * response in one blocking call or streamed it token by token — tool calls need a complete
     * response either way, so the streaming has to be finished before the loop can act on it.
     */
    @FunctionalInterface
    public interface Turn {
        ChatResponse run(List<ChatMessage> messages, List<ToolSpecification> specs);
    }

    /** A blocking turn, for adapters that don't stream. */
    public static Turn blocking(ChatModel model) {
        return (messages, specs) -> model.chat(specs == null || specs.isEmpty()
                ? ChatRequest.builder().messages(messages).build()
                : ChatRequest.builder().messages(messages).toolSpecifications(specs).build());
    }

    /** The answer, plus what the model did to arrive at it. */
    public record Reply(String text, ToolTelemetry telemetry) {}

    public String chat(ChatModel model, List<ChatMessage> messages) {
        return chat(model, messages, null);
    }

    public String chat(ChatModel model, List<ChatMessage> messages, String repoPath) {
        return run(blocking(model), messages, repoPath, LlmPort.StreamSink.NONE).text();
    }

    public Reply run(Turn turn, List<ChatMessage> messages, String repoPath, LlmPort.StreamSink sink) {
        ToolTelemetry tel = new ToolTelemetry();
        String text = chat(turn, messages, repoPath, sink, tel);
        return new Reply(text, tel);
    }

    /**
     * Chat with tool support. {@code messages} is mutated as the exchange grows.
     *
     * @param repoPath the repository the request is about, or null — when set, the built-in repo
     *                 tools are added to whatever MCP tools the user has enabled
     * @return the model's final text, or {@code null} if it never produced any
     */
    private String chat(Turn turn, List<ChatMessage> messages, String repoPath,
                        LlmPort.StreamSink sink, ToolTelemetry tel) {
        List<McpTools.Tool> tools = new ArrayList<>(repoTools.tools(repoPath));
        tools.addAll(mcp.tools());
        List<ToolSpecification> specs = tools.stream().map(McpTools.Tool::spec).toList();
        if (specs.isEmpty()) {
            return text(turn.run(messages, List.of()));
        }
        tel.toolsOffered();
        withToolGuidance(messages, tools);
        // Smaller models get stuck re-calling the same tool with the same arguments; remember what
        // we've already run so the loop can say "you have this already" instead of burning steps.
        java.util.Map<String, String> seen = new java.util.LinkedHashMap<>();
        int spinning = 0; // consecutive steps that asked only for things already fetched
        for (int step = 0; step < MAX_STEPS; step++) {
            tel.step();
            ChatResponse resp;
            try {
                resp = turn.run(messages, specs);
            } catch (RuntimeException e) {
                // Plenty of local models simply can't do tool calling. Rather than fail the turn,
                // drop the tools and let the model answer from context alone.
                if (step == 0) {
                    log.info("Model rejected tool specifications ({}) — retrying without tools", e.getMessage());
                    return text(turn.run(messages, List.of()));
                }
                throw e;
            }
            AiMessage ai = resp.aiMessage();
            if (ai == null) return null;
            int stepsLeft = MAX_STEPS - step - 1;
            boolean allRepeats;

            if (!ai.hasToolExecutionRequests()) {
                // Several local models (qwen2.5-coder, qwen3-coder…) print a tool call as text
                // instead of returning a structured one, depending on their Ollama template. Taking
                // them at their word is the difference between MCP working on local models and not.
                List<ToolExecutionRequest> parsed = TextToolCalls.parse(text(resp), tools);
                if (parsed.isEmpty()) return text(resp);
                messages.add(ai);
                allRepeats = true;
                for (ToolExecutionRequest req : parsed) {
                    sink.status(activity(req));
                    Outcome o = runTool(req, repoPath, seen, tel);
                    allRepeats &= o.repeat();
                    // Fed back as a plain message: a tool-result message without a matching
                    // structured call confuses some chat templates.
                    messages.add(dev.langchain4j.data.message.UserMessage.from(
                            "Result of " + req.name() + ":\n" + withBudget(o.text(), stepsLeft)));
                }
            } else {
                messages.add(ai);
                allRepeats = true;
                for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                    sink.status(activity(req));
                    Outcome o = runTool(req, repoPath, seen, tel);
                    allRepeats &= o.repeat();
                    messages.add(ToolExecutionResultMessage.from(req, withBudget(o.text(), stepsLeft)));
                }
            }

            // A model that asks for nothing new twice running is not making progress, and the
            // remaining steps will go the same way. Stop and make it answer while the results it
            // already has are still the last thing it read.
            spinning = allRepeats ? spinning + 1 : 0;
            if (spinning >= 2) {
                log.info("Tool loop stopped early — {} repeated tool steps with nothing new", spinning);
                tel.stoppedSpinning();
                return finalAnswer(turn, messages,
                        "You are repeating tool calls you have already made, which returns nothing new."
                                + " Stop calling tools and answer now from the results above.");
            }
        }
        // Out of steps. Answering without tools invites confabulation, so say plainly that the
        // budget ran out — a truthful "I couldn't finish" beats an invented answer.
        log.info("Tool loop hit the {}-step cap", MAX_STEPS);
        tel.hitStepCap();
        return finalAnswer(turn, messages,
                "You have used all available tool steps. Answer now using only the tool results above.");
    }

    /** One last turn with no tools offered, so the model has to produce prose. */
    private String finalAnswer(Turn turn, List<ChatMessage> messages, String instruction) {
        messages.add(dev.langchain4j.data.message.UserMessage.from(instruction
                + " If the results were not enough, say exactly what is missing — do not invent details."));
        return text(turn.run(messages, List.of()));
    }

    /** Plain-language description of a tool call, for the activity line the user sees. */
    private static String activity(ToolExecutionRequest req) {
        String args = req.arguments() == null ? "" : req.arguments();
        String detail = null;
        try {
            com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(args.isBlank() ? "{}" : args);
            for (String field : List.of("file", "path", "query", "pattern")) {
                if (n.hasNonNull(field)) { detail = n.get(field).asText(); break; }
            }
        } catch (Exception ignore) { /* a label is never worth failing a turn over */ }
        return switch (req.name()) {
            case RepoTools.LIST -> "listing the repository";
            case RepoTools.READ -> detail == null ? "reading a file" : "reading " + detail;
            case RepoTools.SEARCH -> detail == null ? "searching the repository" : "searching for \"" + detail + "\"";
            default -> detail == null ? req.name() : req.name() + " · " + detail;
        };
    }

    /**
     * Show the remaining budget once it's nearly gone. A model that can see it is about to run out
     * wraps up; one that can't keeps exploring and gets cut off mid-investigation.
     */
    private static String withBudget(String result, int stepsLeft) {
        if (stepsLeft > 2) return result;
        return result + (stepsLeft <= 0
                ? "\n\n[no tool steps left — answer now from what you have]"
                : "\n\n[" + stepsLeft + " tool step" + (stepsLeft == 1 ? "" : "s") + " left — finish gathering and answer]");
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

    private record Outcome(String text, boolean repeat) {}

    /** Execute one tool request, short-circuiting a repeat of something already run this turn. */
    private Outcome runTool(ToolExecutionRequest req, String repoPath,
                            java.util.Map<String, String> seen, ToolTelemetry tel) {
        tel.call(req.name());
        String signature = req.name() + "(" + normalizeArgs(req.arguments()) + ")";
        if (seen.containsKey(signature)) {
            log.info("Tool call repeated, short-circuited: {}", signature);
            tel.repeated();
            return new Outcome("You already called " + signature + " and it returned:\n" + seen.get(signature)
                    + "\n\nDo not call it again — use this result, or answer with what you have.", true);
        }
        String result;
        if (repoTools.handles(req.name())) {
            result = repoTools.call(repoPath, req.name(), req.arguments());
            log.info("Repo tool call: {} -> {} chars", req.name(), result.length());
        } else {
            String server = mcp.serverFor(req.name());
            if (server == null) {
                // Naming the real tools beats "no such tool": a model that invented a name usually
                // wanted one of these, and can pick it on the next step instead of guessing again.
                result = "Tool error: there is no tool named '" + req.name() + "'. The tools you can call are: "
                        + String.join(", ", availableNames(repoPath)) + ".";
                log.info("Unknown tool requested: {}", req.name());
                tel.unknownTool();
            } else {
                result = mcp.call(server, req.name(), req.arguments());
                log.info("MCP tool call: {}/{} -> {} chars", server, req.name(), result.length());
            }
        }
        seen.put(signature, result);
        if (result.startsWith("Tool error:") || result.startsWith("Could not read")) tel.toolError();
        else tel.gatheredSomething();
        return new Outcome(result, false);
    }

    private List<String> availableNames(String repoPath) {
        List<String> names = new ArrayList<>(repoTools.tools(repoPath).stream().map(McpTools.Tool::name).toList());
        names.addAll(mcp.tools().stream().map(McpTools.Tool::name).toList());
        return names;
    }

    /**
     * Canonical form of a call's arguments, so that {@code {"file":"a.js"}} and
     * {@code { "file" : "a.js" }} count as the same call. Without this a model escapes the repeat
     * guard by reformatting its own JSON — which is exactly what an unproductive loop looks like.
     */
    private static String normalizeArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) return "";
        try {
            com.fasterxml.jackson.databind.JsonNode n = JSON.readTree(arguments);
            return canonical(n);
        } catch (Exception e) {
            return arguments.replaceAll("\\s+", "");
        }
    }

    private static String canonical(com.fasterxml.jackson.databind.JsonNode n) {
        if (n.isObject()) {
            java.util.List<String> fields = new ArrayList<>();
            n.fieldNames().forEachRemaining(fields::add);
            java.util.Collections.sort(fields);
            StringBuilder sb = new StringBuilder("{");
            for (String f : fields) sb.append(f).append(':').append(canonical(n.get(f))).append(',');
            return sb.append('}').toString();
        }
        if (n.isArray()) {
            StringBuilder sb = new StringBuilder("[");
            n.forEach(e -> sb.append(canonical(e)).append(','));
            return sb.append(']').toString();
        }
        return n.asText().trim();
    }

    private static String text(ChatResponse resp) {
        return resp == null || resp.aiMessage() == null ? null : resp.aiMessage().text();
    }
}

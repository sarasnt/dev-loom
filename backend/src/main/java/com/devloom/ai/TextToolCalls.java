package com.devloom.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Recovers tool calls that a model wrote as <em>text</em> instead of returning structurally.
 *
 * <p>Ollama only surfaces a structured {@code tool_calls} array when the model's chat template
 * emits one, and several capable local models don't reliably do that — qwen2.5-coder prints a JSON
 * object, qwen3-coder prints an XML-ish {@code <function=…>} block. Ignoring those would mean MCP
 * "works" on paper while doing nothing on the models a local-first user actually runs.
 *
 * <p>Deliberately strict: a call is only recovered when its name matches a tool that is actually
 * available, so ordinary prose that happens to mention a tool is never executed.
 */
final class TextToolCalls {

    private TextToolCalls() {}

    /** qwen3-coder style: {@code <function=name><parameter=key>value</parameter></function>} */
    private static final Pattern FUNCTION_BLOCK =
            Pattern.compile("<function=([\\w.-]+)>(.*?)(?:</function>|\\z)", Pattern.DOTALL);
    private static final Pattern PARAMETER =
            Pattern.compile("<parameter=([\\w.-]+)>(.*?)</parameter>", Pattern.DOTALL);

    static List<ToolExecutionRequest> parse(String text, List<McpTools.Tool> available) {
        List<ToolExecutionRequest> out = new ArrayList<>();
        if (text == null || text.isBlank() || available.isEmpty()) return out;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (McpTools.Tool t : available) names.add(t.name());

        Matcher fn = FUNCTION_BLOCK.matcher(text);
        while (fn.find()) {
            String name = fn.group(1);
            if (!names.contains(name)) continue;
            Map<String, String> args = new LinkedHashMap<>();
            Matcher p = PARAMETER.matcher(fn.group(2));
            while (p.find()) args.put(p.group(1), p.group(2).trim());
            out.add(ToolExecutionRequest.builder().name(name).arguments(toJson(args)).build());
        }
        if (!out.isEmpty()) return out;

        for (String json : jsonObjects(text)) {
            String name = field(json, "name");
            if (name == null || !names.contains(name)) continue;
            String args = objectField(json, "arguments");
            if (args == null) args = objectField(json, "parameters");
            out.add(ToolExecutionRequest.builder().name(name).arguments(args == null ? "{}" : args).build());
        }
        return out;
    }

    /** Every balanced {@code {...}} span in the text (models wrap calls in fences or tags). */
    private static List<String> jsonObjects(String text) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inString = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}' && depth > 0 && --depth == 0 && start >= 0) {
                out.add(text.substring(start, i + 1));
                if (out.size() >= 8) return out; // a sane ceiling; models don't batch dozens
            }
        }
        return out;
    }

    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /** The raw JSON object value of a key, brace-balanced so nested objects survive. */
    private static String objectField(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\{").matcher(json);
        if (!m.find()) return null;
        int start = m.end() - 1, depth = 0;
        boolean inString = false, escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return json.substring(start, i + 1);
        }
        return null;
    }

    /** Values arrive as strings; emit numbers/booleans unquoted so schemas still validate. */
    private static String toJson(Map<String, String> args) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : args.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            String v = e.getValue();
            if (v.matches("-?\\d+(\\.\\d+)?") || v.equals("true") || v.equals("false")) sb.append(v);
            else sb.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n")).append('"');
        }
        return sb.append('}').toString();
    }
}

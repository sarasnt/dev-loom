package com.devloom.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

/**
 * The repository tools every model gets, without configuring anything.
 *
 * <p>Before this, a Fleet analysis run handed a local model a repository and no way to read it:
 * it saw the branch, the file list and the commit log, and had to answer from that. Asked for a
 * version number it would invent {@code 1.0.0}; asked about a function it would say the contents
 * weren't provided. Claude Code brings its own file tools, so the gap only ever showed on the
 * local models this app is meant to be built around. No amount of prompt wording fixes a missing
 * capability — the model needed the tools.
 *
 * <p>Deliberately few, and deliberately narrow: list, read, search — and, only for an edit run,
 * write. All are scoped to the one repository the run is about (the host agent re-checks every
 * path against it) and none can see {@code .git}. Anything wider belongs in an MCP server the user
 * chose to add.
 *
 * <p>The write tool is withheld entirely from read-only runs rather than described and forbidden:
 * a tool a model can see is a tool it will eventually try, and "don't use this" is a weaker
 * guarantee than not offering it. An edit run gets it, and an edit run lives in its own worktree,
 * so the blast radius of a bad one is a branch you discard.
 */
@Service
public class RepoTools {

    private static final Logger log = LoggerFactory.getLogger(RepoTools.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String LIST = "repo_list_files";
    public static final String READ = "repo_read_file";
    public static final String SEARCH = "repo_search";
    public static final String WRITE = "repo_write_file";

    private final HostAgentClient agent;

    public RepoTools(HostAgentClient agent) {
        this.agent = agent;
    }

    public List<McpTools.Tool> tools(String repoPath) {
        return tools(repoPath, false);
    }

    /**
     * Tool specs for a run scoped to {@code repoPath}; empty when the request isn't about a repo.
     *
     * @param writable whether this run may change files. Read-only runs never see the write tool
     *                 at all rather than being told not to use it — a tool a model can see is a
     *                 tool it will eventually try.
     */
    public List<McpTools.Tool> tools(String repoPath, boolean writable) {
        if (repoPath == null || repoPath.isBlank()) return List.of();
        List<McpTools.Tool> out = new ArrayList<>();

        out.add(spec(LIST, """
                List the files tracked in this repository. Takes no arguments. \
                Start here when you don't know what the repository contains.""",
                JsonObjectSchema.builder().build()));

        out.add(spec(READ, """
                Read one file from this repository and return its contents. \
                Use the repository-relative path exactly as it appears in the file list.""",
                JsonObjectSchema.builder()
                        .addProperty("file", JsonStringSchema.builder()
                                .description("Repository-relative path, e.g. src/index.js").build())
                        .required("file")
                        .build()));

        out.add(spec(SEARCH, """
                Search the repository's files for a literal string and return matching lines with \
                their file and line number. Use this to find where something is defined or used \
                instead of reading files one by one.""",
                JsonObjectSchema.builder()
                        .addProperty("query", JsonStringSchema.builder()
                                .description("Literal text to find (not a regular expression)").build())
                        .addProperty("glob", JsonStringSchema.builder()
                                .description("Optional path filter, e.g. *.js").build())
                        .addProperty("max", JsonIntegerSchema.builder()
                                .description("Maximum matches to return (default 60)").build())
                        .required("query")
                        .build()));

        if (writable) {
            out.add(spec(WRITE, """
                    Write a file in this repository, creating it if needed. Give the COMPLETE new \
                    contents of the file — this replaces it entirely, it is not a patch. Read a \
                    file before rewriting it so you don't drop what was already there.""",
                    JsonObjectSchema.builder()
                            .addProperty("file", JsonStringSchema.builder()
                                    .description("Repository-relative path, e.g. lib/foo/bar.dart").build())
                            .addProperty("content", JsonStringSchema.builder()
                                    .description("The complete new contents of the file").build())
                            .required("file", "content")
                            .build()));
        }
        return out;
    }

    public boolean handles(String toolName) {
        return resolve(toolName) != null;
    }

    /**
     * Map a requested name onto a repo tool, accepting the obvious near-misses.
     *
     * <p>A model that had read the right files and knew what to do next called {@code write_file}
     * — the natural name — got "there is no tool named that", and gave up. The whole run was lost
     * to a prefix. Models reach for the plain name; accepting it costs nothing at runtime, whereas
     * teaching them the exact spelling costs prompt tokens on every run that never needed it.
     *
     * @return the canonical tool name, or null if this isn't a repo tool at all
     */
    public String resolve(String toolName) {
        if (toolName == null) return null;
        String n = toolName.trim().toLowerCase();
        if (n.startsWith("repo_")) n = n.substring("repo_".length());
        return switch (n) {
            case "list_files", "list", "ls", "files" -> LIST;
            case "read_file", "read", "cat", "open_file" -> READ;
            case "search", "grep", "find", "search_files" -> SEARCH;
            case "write_file", "write", "create_file", "edit_file", "save_file" -> WRITE;
            default -> null;
        };
    }

    /** Execute one repo tool. Returns text for the model — including failures, phrased as guidance. */
    public String call(String repoPath, String toolName, String argumentsJson) {
        try {
            JsonNode args = argumentsJson == null || argumentsJson.isBlank()
                    ? JSON.createObjectNode() : JSON.readTree(argumentsJson);
            String canonical = resolve(toolName);
            return switch (canonical == null ? "" : canonical) {
                case LIST -> list(repoPath);
                case READ -> read(repoPath, text(args, "file"));
                case SEARCH -> search(repoPath, text(args, "query"), text(args, "glob"), args.path("max").asInt(60));
                case WRITE -> write(repoPath, text(args, "file"), text(args, "content"));
                default -> "Tool error: " + toolName + " is not a repository tool.";
            };
        } catch (Exception e) {
            log.warn("Repo tool {} failed: {}", toolName, e.toString());
            return "Tool error: " + e.getMessage();
        }
    }

    private String list(String repoPath) {
        // 120, not 400. On a 509-file repo the full listing was 20KB of context that the model then
        // summarised back instead of doing the task. A short list plus the true count orients it;
        // beyond that, searching is the right move and the truncation note says so.
        Map<String, Object> r = agent.files(repoPath, 120);
        if (r.get("files") instanceof List<?> files && !files.isEmpty()) {
            // State the count rather than leaving it to be counted. The number is known here, and
            // counting a list is the arithmetic language models are worst at — asked how many files
            // a six-file repo had, a model that had just been shown all six answered five.
            Object total = r.get("total") == null ? files.size() : r.get("total");
            StringBuilder sb = new StringBuilder("This repository has ").append(total)
                    .append(" tracked file").append("1".equals(String.valueOf(total)) ? "" : "s").append(":\n");
            for (Object f : files) sb.append(f).append('\n');
            if (Boolean.TRUE.equals(r.get("truncated"))) {
                sb.append("[only the first ").append(files.size()).append(" are listed]\n");
            }
            return sb.toString().stripTrailing();
        }
        return "The repository has no tracked files.";
    }

    private String read(String repoPath, String file) {
        if (file == null || file.isBlank()) {
            return "Tool error: 'file' is required — give the repository-relative path of the file to read.";
        }
        Map<String, Object> r = agent.readFile(repoPath, file, 60_000);
        Object err = r.get("error");
        if (err != null) {
            // Hand back the file list with the error: "no such file" is nearly always a guessed
            // path, and a model that can see the real ones corrects itself in a single step.
            return "Could not read " + file + ": " + err + "\n\nThe files in this repository are:\n" + list(repoPath);
        }
        String text = String.valueOf(r.getOrDefault("text", ""));
        if (Boolean.TRUE.equals(r.get("truncated"))) {
            text += "\n\n[truncated — this file is " + r.get("bytes") + " bytes; search it instead of reading it whole]";
        }
        return "=== " + file + " ===\n" + text;
    }

    private String search(String repoPath, String query, String glob, int max) {
        if (query == null || query.isBlank()) {
            return "Tool error: 'query' is required — give the literal text to search for.";
        }
        Map<String, Object> r = agent.grep(repoPath, query, glob, max <= 0 ? 60 : max);
        if (r.get("matches") instanceof List<?> matches) {
            if (matches.isEmpty()) return "No matches for \"" + query + "\" in this repository.";
            StringBuilder sb = new StringBuilder();
            for (Object o : matches) {
                if (o instanceof Map<?, ?> m) {
                    sb.append(m.get("file")).append(':').append(m.get("line")).append(": ").append(m.get("text")).append('\n');
                }
            }
            return sb.toString().stripTrailing();
        }
        return "Search failed: " + r.getOrDefault("error", "unknown error");
    }

    private String write(String repoPath, String file, String content) {
        if (file == null || file.isBlank()) return "Tool error: 'file' is required.";
        if (content == null) return "Tool error: 'content' is required — the complete new file contents.";
        Map<String, Object> r = agent.writeFile(repoPath, file, content);
        Object err = r.get("error");
        if (err != null) return "Could not write " + file + ": " + err;
        log.info("Repo write: {} ({} bytes, created={})", file, r.get("bytes"), r.get("created"));
        return (Boolean.TRUE.equals(r.get("created")) ? "Created " : "Updated ") + file
                + " (" + r.get("bytes") + " bytes).";
    }

    private static McpTools.Tool spec(String name, String description, JsonObjectSchema params) {
        return new McpTools.Tool("repo", name,
                ToolSpecification.builder().name(name).description(description).parameters(params).build());
    }

    private static String text(JsonNode args, String field) {
        JsonNode n = args.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}

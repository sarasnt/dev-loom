package com.devloom.integrations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.devloom.workmodel.WorkItemEntity;

/**
 * Notion connector (docs/SPEC-sources.md). Lists pages/databases shared with the integration
 * and classifies each page by its parent: a database row → a task (tagged with the database
 * name); any other page → a note. Databases themselves are not items.
 */
@Component
public class NotionConnector implements SourceConnector {

    private static final Logger log = LoggerFactory.getLogger(NotionConnector.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    private final String baseUrl;
    private final String version;
    private final int maxItems;
    private final int maxPerDb;

    public NotionConnector(
            @Value("${devloom.notion.base-url:https://api.notion.com}") String baseUrl,
            @Value("${devloom.notion.version:2022-06-28}") String version,
            @Value("${devloom.notion.max-items:300}") int maxItems,
            @Value("${devloom.notion.max-per-db:100}") int maxPerDb) {
        this.baseUrl = baseUrl;
        this.version = version;
        this.maxItems = maxItems;
        this.maxPerDb = maxPerDb;
    }

    @Override
    public String type() {
        return "notion";
    }

    @Override
    public SetupDescriptor.Type describe() {
        return new SetupDescriptor.Type("notion", "Notion", List.of(
                new SetupDescriptor.Deployment("cloud", "Cloud", List.of(
                        SetupDescriptor.Field.secret("token", "Integration token", "ntn_… / secret_…")))));
    }

    @Override
    public List<WorkItemEntity> fetch(SourceInstanceEntity inst, Map<String, String> secrets) {
        String token = secrets.getOrDefault("token", "");
        String source = inst.getName();
        RestClient http = RestClient.builder()
                .baseUrl(inst.getBaseUrl() == null || inst.getBaseUrl().isBlank() ? baseUrl : inst.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Notion-Version", version)
                .defaultHeader("Content-Type", "application/json")
                .build();
        try {
            List<Map<String, Object>> all = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                Map<String, Object> body = new HashMap<>();
                body.put("page_size", 100);
                if (cursor != null) body.put("start_cursor", cursor);
                Map<String, Object> resp = http.post().uri("/v1/search").body(body).retrieve().body(MAP);
                if (resp == null) break;
                for (Object o : asList(resp.get("results"))) all.add(asMap(o));
                cursor = Boolean.TRUE.equals(resp.get("has_more")) ? str(resp, "next_cursor") : null;
            } while (cursor != null && !cursor.isBlank() && ++pages < 15 && all.size() < maxItems * 3);

            Map<String, String> dbTitle = new HashMap<>();
            for (Map<String, Object> r : all) {
                if ("database".equals(str(r, "object"))) {
                    dbTitle.put(clip(str(r, "id").replace("-", "")), extractTitle(r));
                }
            }

            List<WorkItemEntity> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            int[] order = {200};

            for (Map<String, Object> r : all) {
                if (out.size() >= maxItems) break;
                if (!"page".equals(str(r, "object"))) continue;
                String extId = clip(str(r, "id").replace("-", ""));
                if (!seen.add(extId)) continue;
                String title = extractTitle(r);
                Map<String, Object> parent = asMap(r.get("parent"));
                if ("database_id".equals(str(parent, "type"))) {
                    String dbId = clip(str(parent, "database_id").replace("-", ""));
                    addTask(out, extId, title, extractStatus(r), dbTitle.getOrDefault(dbId, "Notion"), order, source, pageUrl(r, extId));
                } else {
                    out.add(WorkItemEntity.create(extId, "doc",
                            title.isBlank() ? "(untitled page)" : title,
                            "note", "info", "", source, order[0]++)
                            .withDetail("Notion page shared with DevLoom.", null)
                            .withUrl(pageUrl(r, extId)));
                }
            }

            for (Map<String, Object> r : all) {
                if ("database".equals(str(r, "object"))) {
                    addDatabaseRows(http, str(r, "id"), extractTitle(r), out, seen, order, source);
                }
            }

            log.info("Notion sync [{}]: {} items ({} databases)", source, out.size(), dbTitle.size());
            return out;
        } catch (Exception e) {
            log.warn("Notion sync failed [{}]: {}", source, e.getMessage());
            throw new IllegalStateException("Notion fetch failed", e);
        }
    }

    private void addTask(List<WorkItemEntity> out, String extId, String title, String status,
                         String dbName, int[] order, String source, String url) {
        String tag = dbName.replace(",", " ").strip();
        String tone = switch (status.toLowerCase()) {
            case "done", "complete", "completed", "closed", "shipped", "archived" -> "healthy";
            case "in progress", "doing", "in review", "started", "wip" -> "warn";
            default -> "info";
        };
        out.add(WorkItemEntity.create(extId, "task",
                title.isBlank() ? "(untitled task)" : title,
                status.isBlank() ? "task" : status, tone,
                tag, source, order[0]++)
                .withDetail("From Notion database: " + tag, null)
                .withUrl(url));
    }

    /**
     * The page's canonical web URL. Notion's API returns a {@code url} on every page/row; when
     * it's absent we fall back to {@code notion.so/<id>}, which resolves to the same page.
     */
    private static String pageUrl(Map<String, Object> obj, String extId) {
        String u = str(obj, "url");
        return u.isBlank() ? "https://www.notion.so/" + extId : u;
    }

    private void addDatabaseRows(RestClient http, String rawDbId, String dbName, List<WorkItemEntity> out,
                                 Set<String> seen, int[] order, String source) {
        try {
            Map<String, Object> resp = http.post()
                    .uri("/v1/databases/" + rawDbId + "/query")
                    .body(Map.of("page_size", Math.min(maxPerDb, 100)))
                    .retrieve().body(MAP);
            int added = 0;
            for (Object o : asList(resp == null ? null : resp.get("results"))) {
                if (added >= maxPerDb || out.size() >= maxItems) break;
                Map<String, Object> row = asMap(o);
                String extId = clip(str(row, "id").replace("-", ""));
                if (!seen.add(extId)) continue;
                addTask(out, extId, extractTitle(row), extractStatus(row), dbName, order, source, pageUrl(row, extId));
                added++;
            }
        } catch (Exception e) {
            log.warn("Notion database query failed for {}: {}", dbName, e.getMessage());
        }
    }

    private static String clip(String id) {
        return id.length() > 60 ? id.substring(0, 60) : id;
    }

    @SuppressWarnings("unchecked")
    private String extractStatus(Map<String, Object> row) {
        Object props = row.get("properties");
        if (!(props instanceof Map<?, ?> pm)) return "";
        String statusTyped = null, namedSelect = null, anySelect = null;
        for (Map.Entry<?, ?> e : pm.entrySet()) {
            String propName = String.valueOf(e.getKey()).toLowerCase();
            Map<String, Object> prop = asMap(e.getValue());
            String type = str(prop, "type");
            if ("status".equals(type)) {
                String v = str(asMap(prop.get("status")), "name");
                if (!v.isBlank()) statusTyped = v;
            } else if ("select".equals(type)) {
                String v = str(asMap(prop.get("select")), "name");
                if (!v.isBlank()) {
                    if (propName.contains("status") || propName.contains("stage") || propName.contains("state")) {
                        namedSelect = v;
                    } else if (anySelect == null) {
                        anySelect = v;
                    }
                }
            }
        }
        return statusTyped != null ? statusTyped : namedSelect != null ? namedSelect
                : anySelect != null ? anySelect : "";
    }

    @SuppressWarnings("unchecked")
    private String extractTitle(Map<String, Object> obj) {
        Object props = obj.get("properties");
        if (props instanceof Map<?, ?> pm) {
            for (Object v : pm.values()) {
                Map<String, Object> prop = asMap(v);
                if ("title".equals(str(prop, "type"))) {
                    String t = joinPlain(prop.get("title"));
                    if (!t.isBlank()) return t;
                }
            }
        }
        return joinPlain(obj.get("title"));
    }

    private static String joinPlain(Object richTextArray) {
        StringBuilder sb = new StringBuilder();
        for (Object o : asList(richTextArray)) {
            sb.append(str(asMap(o), "plain_text"));
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static List<?> asList(Object o) {
        return o instanceof List ? (List<?>) o : List.of();
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString();
    }
}

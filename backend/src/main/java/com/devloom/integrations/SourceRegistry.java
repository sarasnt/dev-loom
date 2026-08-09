package com.devloom.integrations;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** Maps a source type → its connector (docs/SPEC-sources.md §6). */
@Component
public class SourceRegistry {

    private final Map<String, SourceConnector> byType = new LinkedHashMap<>();

    public SourceRegistry(List<SourceConnector> connectors) {
        for (SourceConnector c : connectors) {
            byType.put(c.type(), c);
        }
    }

    public Optional<SourceConnector> forType(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    public List<SetupDescriptor.Type> descriptors() {
        return byType.values().stream().map(SourceConnector::describe).toList();
    }
}

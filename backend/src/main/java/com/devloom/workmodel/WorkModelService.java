package com.devloom.workmodel;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;

import com.devloom.api.Dto;

/** Reads the unified WorkItem table and maps to transport DTOs. */
@Service
public class WorkModelService {

    private final WorkItemRepository repo;

    public WorkModelService(WorkItemRepository repo) {
        this.repo = repo;
    }

    public List<Dto.WorkRow> allWork() {
        return repo.findAllByOrderBySortOrderAsc().stream().map(this::toRow).toList();
    }

    private Dto.WorkRow toRow(WorkItemEntity e) {
        List<String> meta = e.getMetaCsv().isBlank() ? List.of() : Arrays.asList(e.getMetaCsv().split(","));
        return new Dto.WorkRow(
                e.getExtId(), e.getType(), glyph(e.getType()), e.getTitle(),
                e.getStatus(), e.getStatusTone(), meta, e.getSource());
    }

    /** Presentation glyph derived from domain type (kept out of the DB). */
    private String glyph(String type) {
        return switch (type) {
            case "build" -> "⚡";
            case "task" -> "◆";
            case "review", "calendar" -> "◷";
            case "doc" -> "▤";
            default -> "⎇"; // pr, stale
        };
    }
}

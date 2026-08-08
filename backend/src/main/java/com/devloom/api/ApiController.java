package com.devloom.api;

import java.util.List;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devloom.integrations.SyncService;
import com.devloom.workmodel.WorkModelService;

/** All read endpoints for the MVP frontend (SPEC.md §34). Shapes match frontend/src/types.ts. */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private final TodayService todayService;
    private final WorkModelService workModel;
    private final FixtureData fixtures;
    private final SyncService syncService;

    public ApiController(TodayService todayService, WorkModelService workModel,
                         FixtureData fixtures, SyncService syncService) {
        this.todayService = todayService;
        this.workModel = workModel;
        this.fixtures = fixtures;
        this.syncService = syncService;
    }

    /** Trigger an on-demand sync of a source (e.g. Jira). */
    @PostMapping("/integrations/{source}/sync")
    public Map<String, Object> sync(@PathVariable String source) {
        int n = syncService.sync(source);
        return Map.<String, Object>of("source", source, "ingested", n);
    }

    @GetMapping("/today")
    public Dto.Today today() {
        return todayService.today();
    }

    @GetMapping("/work")
    public List<Dto.WorkRow> work() {
        return workModel.allWork();
    }

    @GetMapping({"/builds/{id}", "/builds"})
    public Dto.BuildFailure build(@PathVariable(required = false) String id) {
        return fixtures.buildFailure(id == null ? "1893" : id);
    }

    @GetMapping({"/handoffs/{id}", "/handoffs"})
    public Dto.Handoff handoff(@PathVariable(required = false) String id) {
        return fixtures.handoff(id == null ? "h1" : id);
    }

    @GetMapping("/integrations")
    public List<Dto.Integration> integrations() {
        return fixtures.integrations();
    }

    @GetMapping("/providers")
    public Dto.Providers providers() {
        return fixtures.providers();
    }

    @GetMapping("/privacy")
    public Dto.Privacy privacy() {
        return fixtures.privacy();
    }

    @GetMapping("/brainstorm")
    public Dto.Brainstorm brainstorm() {
        return fixtures.brainstorm();
    }

    @GetMapping("/onboarding")
    public List<Dto.OnboardStep> onboarding() {
        return fixtures.onboarding();
    }
}

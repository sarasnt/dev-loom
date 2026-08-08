package com.devloom.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devloom.workmodel.WorkModelService;

/** All read endpoints for the MVP frontend (SPEC.md §34). Shapes match frontend/src/types.ts. */
@RestController
@RequestMapping("/api/v1")
public class ApiController {

    private final TodayService todayService;
    private final WorkModelService workModel;
    private final FixtureData fixtures;

    public ApiController(TodayService todayService, WorkModelService workModel, FixtureData fixtures) {
        this.todayService = todayService;
        this.workModel = workModel;
        this.fixtures = fixtures;
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

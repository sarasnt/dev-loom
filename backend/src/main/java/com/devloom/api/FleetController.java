package com.devloom.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devloom.fleet.FleetService;

/** Fleet — concurrent AI runs launched from DevLoom (spec §5–§8). */
@RestController
@RequestMapping("/api/v1/fleet")
public class FleetController {

    private final FleetService fleet;

    public FleetController(FleetService fleet) {
        this.fleet = fleet;
    }

    @GetMapping("/runs")
    public List<Dto.AgentRun> runs() {
        return fleet.list();
    }

    @PostMapping("/runs")
    public Dto.AgentRun launch(@RequestBody Dto.RunLaunch body) {
        return fleet.launch(body);
    }

    @GetMapping("/runs/{id}")
    public Dto.AgentRun run(@PathVariable String id) {
        return fleet.get(id);
    }

    @PostMapping("/runs/{id}/cancel")
    public Dto.AgentRun cancel(@PathVariable String id) {
        return fleet.cancel(id);
    }
}

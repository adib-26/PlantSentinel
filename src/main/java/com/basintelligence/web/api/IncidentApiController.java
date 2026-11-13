package com.basintelligence.web.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.basintelligence.machine.MachineService;
import com.basintelligence.telemetry.simulation.IncidentSimulator;
import com.basintelligence.telemetry.simulation.Scenario;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Simulated machine incidents for rehearsing the detection-to-approval journey on simulated machines. */
@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "Simulated incidents")
public class IncidentApiController {

    public record StartIncident(@NotNull Scenario fault,
            @Min(IncidentSimulator.MIN_RAMP_MINUTES) @Max(IncidentSimulator.MAX_RAMP_MINUTES) Integer rampMinutes) {
    }

    private final IncidentSimulator incidents;
    private final MachineService machines;

    public IncidentApiController(IncidentSimulator incidents, MachineService machines) {
        this.incidents = incidents;
        this.machines = machines;
    }

    @PostMapping("/machines/{code}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ENGINEER')")
    @Operation(summary = "Simulate a fault on a simulated machine; an investigation starts when the fault peaks")
    public IncidentSimulator.Incident start(@PathVariable String code, @Valid @RequestBody StartIncident body,
            Authentication auth) {
        return incidents.start(code, body.fault(), body.rampMinutes() == null ? 5 : body.rampMinutes(), auth.getName());
    }

    @GetMapping("/machines/{code}/incidents")
    public List<IncidentSimulator.Incident> list(@PathVariable String code) {
        return incidents.forMachine(machines.getByCode(code).id(), 20);
    }

    @PostMapping("/incidents/{id}/stop")
    @PreAuthorize("hasRole('ENGINEER')")
    public IncidentSimulator.Incident stop(@PathVariable long id, Authentication auth) {
        return incidents.stop(id, auth.getName());
    }
}

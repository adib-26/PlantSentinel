package com.basintelligence.web.api;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.basintelligence.analytics.AnomalyEvent;
import com.basintelligence.analytics.TelemetryAnalysis;
import com.basintelligence.common.NotFoundException;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.risk.RiskAssessment;
import com.basintelligence.risk.RiskService;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.vision.Inspection;
import com.basintelligence.vision.InspectionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/machines")
@Validated
@Tag(name = "Machines")
public class MachineApiController {

    private final MachineService machines;
    private final TelemetryService telemetry;
    private final RiskService risk;
    private final InspectionService inspections;
    private final com.basintelligence.failure.FailureEventService failures;
    private final com.basintelligence.telemetry.SensorFaultService sensorFaults;

    public MachineApiController(MachineService machines, TelemetryService telemetry, RiskService risk,
            InspectionService inspections, com.basintelligence.failure.FailureEventService failures,
            com.basintelligence.telemetry.SensorFaultService sensorFaults) {
        this.failures = failures;
        this.sensorFaults = sensorFaults;
        this.machines = machines;
        this.telemetry = telemetry;
        this.risk = risk;
        this.inspections = inspections;
    }

    @GetMapping
    @Operation(summary = "List machines with health status")
    public List<Machine> list() {
        return machines.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ENGINEER')")
    @Operation(summary = "Register a machine")
    public Machine register(@Valid @RequestBody MachineService.RegisterMachine request, Authentication auth) {
        return machines.register(request, auth.getName());
    }

    @GetMapping("/{code}")
    public Machine get(@PathVariable String code) {
        return machines.getByCode(code);
    }

    @GetMapping("/{code}/analysis")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.PREDICTION)
    @Operation(summary = "Telemetry trends and multivariate anomaly score over a window")
    public TelemetryAnalysis analysis(@PathVariable String code,
            @RequestParam(defaultValue = "120") @Min(10) @Max(1440) int minutes) {
        return telemetry.analyze(machines.getByCode(code), Duration.ofMinutes(minutes));
    }

    @GetMapping("/{code}/anomalies")
    public List<AnomalyEvent> anomalies(@PathVariable String code,
            @RequestParam(defaultValue = "6") @Min(1) @Max(168) int hours) {
        return telemetry.anomalies(machines.getByCode(code).id(), Duration.ofHours(hours), 200);
    }

    @GetMapping("/{code}/risk")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.PREDICTION)
    @Operation(summary = "Explainable failure-risk estimate")
    public RiskAssessment risk(@PathVariable String code) {
        return risk.assess(machines.getByCode(code)).orElseThrow(() -> new NotFoundException("No telemetry yet for " + code));
    }

    public record ConfirmFailure(@jakarta.validation.constraints.NotNull com.basintelligence.failure.FailureEventService.FailureMode mode,
            @NotBlank @Size(max = 2000) String description, java.time.Instant occurredAt, Long maintenanceRecordId) {
    }

    @PostMapping("/{code}/failures")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ENGINEER', 'TECHNICIAN')")
    @Operation(summary = "Record a confirmed failure (a person inspected the machine). Predictions never create these.")
    public com.basintelligence.failure.FailureEventService.FailureEvent confirmFailure(@PathVariable String code,
            @Valid @RequestBody ConfirmFailure body, Authentication auth) {
        return failures.confirm(machines.getByCode(code).id(), body.mode(), body.description(), body.occurredAt(),
                body.maintenanceRecordId(), auth.getName());
    }

    @GetMapping("/{code}/failures")
    public List<com.basintelligence.failure.FailureEventService.FailureEvent> failures(@PathVariable String code) {
        return failures.forMachine(machines.getByCode(code).id(), 100);
    }

    @GetMapping("/{code}/sensor-faults")
    @Operation(summary = "Readings rejected as untrustworthy (missing, impossible, stuck, bad timestamp)")
    public List<com.basintelligence.telemetry.SensorFaultService.SensorFault> sensorFaults(@PathVariable String code,
            @RequestParam(defaultValue = "24") @Min(1) @Max(168) int hours) {
        return sensorFaults.recent(machines.getByCode(code).id(), Duration.ofHours(hours), 200);
    }

    @PostMapping(path = "/{code}/inspections", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.INSPECTION)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ENGINEER')")
    @Operation(summary = "Inspect a component image for defects (OpenCV + ONNX Runtime)")
    public Inspection inspect(@PathVariable String code, @RequestParam @NotBlank @Size(max = 120) String component,
            @RequestPart("image") MultipartFile image, Authentication auth) throws IOException {
        return inspections.inspect(machines.getByCode(code), component.strip(), image.getOriginalFilename(),
                image.getBytes(), auth.getName());
    }
}

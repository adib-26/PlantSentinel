package com.basintelligence.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.basintelligence.machine.HealthStatus;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Ingestion endpoint for BAS / Mako Server edge nodes, authenticated with the X-API-Key header.
 * <p>
 * The batch envelope is validated strictly (400 if malformed). Each reading is then judged on its own, so one bad
 * sensor frame does not discard a whole batch:
 * <ul>
 * <li>ACCEPTED: stored and analysed.</li>
 * <li>DUPLICATE: the same {@code eventId} was already stored for this machine (a resend after a lost response).</li>
 * <li>SENSOR_FAULT: missing or physically impossible value, or a bad timestamp. Recorded as a sensor fault, not
 * stored as telemetry, never seen by the anomaly detectors.</li>
 * <li>UNKNOWN_MACHINE: the machine code is not registered.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/edge")
@Validated
@Tag(name = "Edge ingestion")
public class EdgeTelemetryController {

    /** Readings further in the future than this are rejected (clock skew guard). */
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    /** Readings older than this are rejected; edge queues hold minutes to hours, not weeks. */
    private static final Duration MAX_AGE = Duration.ofDays(7);

    public enum Outcome {
        ACCEPTED, DUPLICATE, SENSOR_FAULT, UNKNOWN_MACHINE
    }

    /** Channel values are nullable on purpose: a missing value is a sensor fault to record, not a malformed request. */
    public record EdgeReading(
            @NotBlank @Size(max = 40) String machineCode,
            @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._:-]*", message = "use letters, digits, . _ : -") String eventId,
            Instant timestamp,
            Double temperature,
            Double vibration,
            Double rpm,
            Double torque,
            Double pressure) {
    }

    public record EdgeBatch(@NotBlank @Size(max = 80) String gatewayId,
            @NotEmpty @Size(max = 500) List<@Valid EdgeReading> readings) {
    }

    public record ReadingResult(int index, String machineCode, String eventId, Outcome outcome,
            List<SensorPlausibility.Fault> faults) {
    }

    public record IngestResult(String gatewayId, int received, int accepted, int duplicates, int sensorFaults,
            int unknownMachines, Map<String, HealthStatus> machineHealth, List<ReadingResult> rejected) {
    }

    private final MachineRepository machines;
    private final TelemetryService telemetry;
    private final SensorFaultService sensorFaults;
    private final Clock clock;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public EdgeTelemetryController(MachineRepository machines, TelemetryService telemetry, SensorFaultService sensorFaults,
            Clock clock, io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.machines = machines;
        this.telemetry = telemetry;
        this.sensorFaults = sensorFaults;
        this.clock = clock;
    }

    @PostMapping("/telemetry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Ingest a batch of telemetry readings from an edge gateway",
            security = @SecurityRequirement(name = "edgeKey"))
    public IngestResult ingest(@Valid @RequestBody EdgeBatch batch,
            @org.springframework.security.core.annotation.AuthenticationPrincipal EdgePrincipal sender) {
        // A registered gateway is identified by its key, not by the gatewayId it declares in the body.
        String gatewayId = sender != null && sender.registered() ? sender.gatewayCode() : batch.gatewayId();
        meters.summary(com.basintelligence.common.BasMetrics.EDGE_BATCH_SIZE).record(batch.readings().size());
        Instant now = clock.instant();
        Map<String, HealthStatus> health = new LinkedHashMap<>();
        Map<String, Optional<Machine>> cache = new LinkedHashMap<>();
        List<ReadingResult> rejected = new ArrayList<>();
        int accepted = 0, duplicates = 0, faults = 0, unknown = 0;
        for (int i = 0; i < batch.readings().size(); i++) {
            EdgeReading r = batch.readings().get(i);
            String code = r.machineCode().strip().toUpperCase(java.util.Locale.ROOT);
            // A gateway may only post for machines of its own factory; others look unregistered to it.
            Optional<Machine> machine = cache.computeIfAbsent(code, c -> machines.findByCode(c)
                    .filter(m -> sender == null || sender.mayPostFor(m.factoryId())));
            if (machine.isEmpty()) {
                unknown++;
                meters.counter(com.basintelligence.common.BasMetrics.TELEMETRY_INVALID, "reason", "UNKNOWN_MACHINE").increment();
                rejected.add(new ReadingResult(i, code, r.eventId(), Outcome.UNKNOWN_MACHINE, List.of()));
                continue;
            }
            Machine m = machine.get();
            Instant at = r.timestamp() == null ? now : r.timestamp();
            List<SensorPlausibility.Fault> problems = new ArrayList<>(
                    SensorPlausibility.check(r.temperature(), r.vibration(), r.rpm(), r.torque(), r.pressure()));
            if (at.isAfter(now.plus(MAX_FUTURE_SKEW)) || at.isBefore(now.minus(MAX_AGE))) {
                problems.add(new SensorPlausibility.Fault(SensorPlausibility.FaultType.INVALID_TIMESTAMP, "TIMESTAMP",
                        at.toString(), "Timestamp " + at + " is more than 5 minutes ahead or 7 days behind the server clock"));
            }
            if (!problems.isEmpty()) {
                faults++;
                problems.forEach(f -> sensorFaults.record(m.id(), at, gatewayId, r.eventId(), f));
                rejected.add(new ReadingResult(i, code, r.eventId(), Outcome.SENSOR_FAULT, problems));
                continue;
            }
            var reading = new TelemetryReading(m.id(), at, r.temperature(), r.vibration(), r.rpm(), r.torque(),
                    r.pressure(), TelemetrySource.EDGE);
            Optional<HealthStatus> result = telemetry.ingestEdge(m, reading, blankToNull(r.eventId()));
            if (result.isEmpty()) {
                duplicates++;
                rejected.add(new ReadingResult(i, code, r.eventId(), Outcome.DUPLICATE, List.of()));
            } else {
                accepted++;
                health.put(code, result.get());
                // reload so the next reading in the batch sees the updated last-seen time
                cache.put(code, machines.findByCode(code).filter(x -> sender == null || sender.mayPostFor(x.factoryId())));
            }
        }
        return new IngestResult(gatewayId, batch.readings().size(), accepted, duplicates, faults, unknown, health,
                rejected);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}

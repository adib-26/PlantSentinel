package com.basintelligence.telemetry.simulation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.basintelligence.common.ActivityTracker;
import com.basintelligence.config.BasProperties;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.telemetry.TelemetryService;

/**
 * Stands in for BAS edge nodes during demos. Machines that have ever received real edge data are skipped, so the
 * simulator and a real Mako Server can run side by side, and an edge outage is not masked by simulated data.
 */
@Component
@ConditionalOnProperty(prefix = "bas.simulator", name = "enabled", havingValue = "true")
public class TelemetrySimulator {

    private static final Logger log = LoggerFactory.getLogger(TelemetrySimulator.class);
    public static final Duration RAMP = Duration.ofHours(6);

    private final MachineService machines;
    private final TelemetryService telemetry;
    private final BasProperties properties;
    private final ActivityTracker activity;
    private final Clock clock;
    private final IncidentSimulator incidents;
    private final TelemetryGenerator generator = new TelemetryGenerator(new Random(), RAMP, 0.004);
    private final Map<String, Scenario> scenarios = new ConcurrentHashMap<>();

    public TelemetrySimulator(MachineService machines, TelemetryService telemetry, BasProperties properties,
            ActivityTracker activity, Clock clock, IncidentSimulator incidents) {
        this.incidents = incidents;
        this.activity = activity;
        this.machines = machines;
        this.telemetry = telemetry;
        this.properties = properties;
        this.clock = clock;
    }

    public void assign(String machineCode, Scenario scenario) {
        scenarios.put(machineCode, scenario);
    }

    public Scenario scenarioOf(String machineCode) {
        return scenarios.getOrDefault(machineCode, DemoScenarios.forCode(machineCode));
    }

    /** Scenario clock starts before registration so seeded history and live data form one curve. */
    public Instant scenarioStart(Machine machine) {
        return machine.createdAt().minus(properties.demo().history());
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "${bas.simulator.interval:10s}")
    public void tick() {
        if (activity.isIdle()) {
            return; // nobody is watching: let the database sleep
        }
        Instant now = clock.instant();
        for (Machine machine : machines.list()) {
            // Never simulate a machine that a real edge node feeds: an edge outage must stay visible as missing data.
            if (telemetry.isEdgeManaged(machine.id()) || telemetry.hasRecentEdgeData(machine.id(), Duration.ofMinutes(5))) {
                continue;
            }
            if (incidents.isActive(machine.id())) {
                continue; // a simulated incident writes this machine's readings
            }
            try {
                telemetry.ingest(machine, generator.generate(machine.id(), machine.type(), scenarioOf(machine.code()),
                        scenarioStart(machine), now));
            } catch (RuntimeException e) {
                log.warn("Simulator failed for {}: {}", machine.code(), e.getMessage());
            }
        }
    }
}

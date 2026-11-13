package com.basintelligence.telemetry.simulation;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Advances active simulated incidents every 10 seconds. Runs even when the background simulator is off, so an
 * incident can be rehearsed on any simulated machine. Tests disable it and call {@link IncidentSimulator#advance}.
 */
@Component
@ConditionalOnProperty(prefix = "bas.incidents", name = "scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class IncidentScheduler {

    private final IncidentSimulator incidents;
    private final Clock clock;

    public IncidentScheduler(IncidentSimulator incidents, Clock clock) {
        this.incidents = incidents;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT7S", fixedDelayString = "PT10S")
    public void tick() {
        incidents.advance(clock.instant());
    }
}

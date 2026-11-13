package com.basintelligence.machine;

import java.time.Instant;
import java.util.UUID;

/** A registered machine (stored in the {@code devices} table). URLs and the edge API use the readable {@code code}. */
public record Machine(
        UUID id,
        UUID factoryId,
        String code,
        String name,
        MachineType type,
        String location,
        HealthStatus healthStatus,
        Instant lastSeenAt,
        Instant createdAt) {
}

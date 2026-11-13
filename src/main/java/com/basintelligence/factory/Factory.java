package com.basintelligence.factory;

import java.time.Instant;
import java.util.UUID;

public record Factory(UUID id, String code, String name, String location, Instant createdAt) {
}

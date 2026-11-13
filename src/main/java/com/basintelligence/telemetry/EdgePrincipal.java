package com.basintelligence.telemetry;

import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The authenticated sender of an edge request. A registered gateway has its own key and is bound to one factory.
 * The legacy shared key ({@code bas.security.edge-api-key}) has no gateway identity and no factory restriction.
 */
public record EdgePrincipal(String gatewayCode, UUID factoryId) implements AuthenticatedPrincipal {

    public static final String SHARED_KEY_NAME = "edge-gateway";

    public static EdgePrincipal sharedKey() {
        return new EdgePrincipal(null, null);
    }

    public boolean registered() {
        return gatewayCode != null;
    }

    /** Readings for machines outside this factory are refused. Null means any factory (shared key). */
    public boolean mayPostFor(UUID machineFactoryId) {
        return factoryId == null || factoryId.equals(machineFactoryId);
    }

    @Override
    public String getName() {
        return registered() ? "gateway:" + gatewayCode : SHARED_KEY_NAME;
    }
}

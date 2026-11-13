package com.basintelligence.users;

import java.util.Collection;
import java.util.UUID;

/**
 * Which machines the current caller may see. Derived from the caller's roles and factory:
 * <ul>
 * <li>{@link Kind#ALL}: administrators, the Kiro agent and background jobs (no request).</li>
 * <li>{@link Kind#FACTORY}: engineers and viewers, limited to the machines of their factory.</li>
 * <li>{@link Kind#ASSIGNED}: technicians, limited to machines assigned to them (directly or by a work order).</li>
 * <li>{@link Kind#NONE}: anything else, for example a person without a factory. Fails closed.</li>
 * </ul>
 */
public record AccessScope(Kind kind, String username, UUID factoryId) {

    public enum Kind {
        ALL, FACTORY, ASSIGNED, NONE
    }

    public static final AccessScope ALL = new AccessScope(Kind.ALL, null, null);
    public static final AccessScope NONE = new AccessScope(Kind.NONE, null, null);

    public boolean unrestricted() {
        return kind == Kind.ALL;
    }

    /**
     * Derives the scope for a person. Precedence follows the most privileged role: ADMIN, ENGINEER, TECHNICIAN, VIEWER.
     * A technician account that also holds VIEWER is still limited to its assigned machines.
     */
    public static AccessScope forPerson(String username, Collection<String> roles, UUID factoryId) {
        if (roles.contains("ADMIN")) {
            return ALL;
        }
        if (factoryId == null) {
            return NONE;
        }
        if (roles.contains("ENGINEER")) {
            return new AccessScope(Kind.FACTORY, username, factoryId);
        }
        if (roles.contains("TECHNICIAN")) {
            return new AccessScope(Kind.ASSIGNED, username, factoryId);
        }
        if (roles.contains("VIEWER")) {
            return new AccessScope(Kind.FACTORY, username, factoryId);
        }
        return NONE;
    }
}

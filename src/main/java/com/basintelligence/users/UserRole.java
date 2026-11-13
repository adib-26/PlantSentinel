package com.basintelligence.users;

import java.util.Collection;
import java.util.List;

/**
 * The four account types an administrator can choose. Each maps to the Spring Security roles it grants; every type
 * includes VIEWER, which the URL rules require for the dashboard.
 * <ul>
 * <li>ADMIN: manage users, factories, edge gateways and technician assignments; sees every factory; also an engineer.</li>
 * <li>ENGINEER: inspect predictions and images, run investigations, create and review maintenance drafts.</li>
 * <li>TECHNICIAN: view assigned machines and their maintenance records, complete assigned work, confirm failures.</li>
 * <li>VIEWER: read-only dashboard access for one factory.</li>
 * </ul>
 */
public enum UserRole {
    ADMIN(List.of("ADMIN", "ENGINEER", "VIEWER")),
    ENGINEER(List.of("ENGINEER", "VIEWER")),
    TECHNICIAN(List.of("TECHNICIAN", "VIEWER")),
    VIEWER(List.of("VIEWER"));

    private final List<String> grants;

    UserRole(List<String> grants) {
        this.grants = grants;
    }

    public List<String> grants() {
        return grants;
    }

    public String csv() {
        return String.join(",", grants);
    }

    /** The most privileged account type present in a set of granted roles. */
    public static UserRole primaryOf(Collection<String> roles) {
        if (roles.contains("ADMIN")) {
            return ADMIN;
        }
        if (roles.contains("ENGINEER")) {
            return ENGINEER;
        }
        if (roles.contains("TECHNICIAN")) {
            return TECHNICIAN;
        }
        return VIEWER;
    }
}

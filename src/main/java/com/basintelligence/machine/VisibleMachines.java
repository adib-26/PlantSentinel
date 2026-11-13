package com.basintelligence.machine;

import java.util.Set;
import java.util.UUID;

/**
 * The machines a caller may see, for filtering lists of investigations, recommendations, work orders and reports.
 * {@code all} means no filter (administrators, the agent, background jobs).
 */
public record VisibleMachines(boolean all, Set<UUID> ids) {

    public static final VisibleMachines EVERYTHING = new VisibleMachines(true, Set.of());

    public VisibleMachines {
        ids = Set.copyOf(ids);
    }

    public static VisibleMachines of(Set<UUID> ids) {
        return new VisibleMachines(false, ids);
    }

    public boolean contains(UUID machineId) {
        return all || ids.contains(machineId);
    }

    /** True when the caller can see no machine at all, so a filtered query can be skipped. */
    public boolean none() {
        return !all && ids.isEmpty();
    }

    /**
     * SQL condition for the given device-id column, bound to the {@code :visibleIds} parameter. The column name is a
     * constant chosen by the caller, never request input.
     */
    public String condition(String column) {
        return all ? "1 = 1" : column + " IN (:visibleIds)";
    }

    /** Value for {@code :visibleIds}; a placeholder when unrestricted so the parameter is always bound. */
    public Set<UUID> parameter() {
        return all ? Set.of(new UUID(0, 0)) : ids;
    }
}

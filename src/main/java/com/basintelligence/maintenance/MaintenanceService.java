package com.basintelligence.maintenance;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.Text;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.VisibleMachines;
import com.basintelligence.users.AccessScope;
import com.basintelligence.users.AccessScopeService;

/** Maintenance history: approved recommendations become PLANNED work that an engineer later completes. */
@Service
public class MaintenanceService {

    public enum Status {
        PLANNED, COMPLETED, CANCELLED
    }

    public record MaintenanceRecord(long id, UUID machineId, Long recommendationId, String action, String priority,
            Status status, String plannedBy, Instant plannedAt, String completedBy, Instant completedAt, String notes,
            String assignedTo, String assignedBy, Instant assignedAt) {
    }

    private static final RowMapper<MaintenanceRecord> MAPPER = (rs, n) -> new MaintenanceRecord(rs.getLong("id"),
            rs.getObject("device_id", UUID.class), (Long) rs.getObject("recommendation_id", Long.class),
            rs.getString("action"), rs.getString("priority"), Status.valueOf(rs.getString("status")),
            rs.getString("planned_by"), instant(rs, "planned_at"), rs.getString("completed_by"),
            instant(rs, "completed_at"), rs.getString("notes"), rs.getString("assigned_to"), rs.getString("assigned_by"),
            instant(rs, "assigned_at"));

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final MachineService machines;
    private final AccessScopeService access;
    private final Clock clock;

    public MaintenanceService(JdbcClient jdbc, AuditService audit, MachineService machines, AccessScopeService access,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.machines = machines;
        this.access = access;
        this.clock = clock;
    }

    public void plan(UUID machineId, long recommendationId, String action, String priority, String plannedBy) {
        jdbc.sql("""
                INSERT INTO maintenance_records (device_id, recommendation_id, action, priority, status, planned_by, planned_at)
                VALUES (:d, :r, :action, :priority, 'PLANNED', :by, :at)""")
                .param("d", machineId).param("r", recommendationId).param("action", Text.truncate(action, 1000))
                .param("priority", priority).param("by", plannedBy).param("at", utc(clock.instant()))
                .update();
    }

    /** Assigns planned work to a technician (an existing user with the TECHNICIAN or ENGINEER role). */
    @Transactional
    public MaintenanceRecord assign(long id, String technician, String actor) {
        MaintenanceRecord record = get(id);
        if (!technicians(machines.get(record.machineId()).factoryId()).contains(technician)) {
            throw new IllegalArgumentException("Unknown technician for this machine's factory: " + technician);
        }
        int updated = jdbc.sql("""
                UPDATE maintenance_records SET assigned_to = :t, assigned_by = :by, assigned_at = :at
                WHERE id = :id AND status = 'PLANNED'""")
                .param("t", technician).param("by", actor).param("at", utc(clock.instant())).param("id", id).update();
        if (updated != 1) {
            throw new InvalidStateException("Only planned work orders can be assigned");
        }
        audit.record(actor, "MAINTENANCE_ASSIGNED", "maintenance:" + id, "assigned to " + technician);
        return get(id);
    }

    /** Usernames the caller can assign work to: technicians and engineers of the caller's factory (all for admins). */
    public List<String> technicians() {
        AccessScope scope = access.current();
        return scope.unrestricted() ? technicians(null) : technicians(scope.factoryId());
    }

    private List<String> technicians(UUID factoryId) {
        return jdbc.sql("SELECT username, roles, factory_id FROM users WHERE enabled = TRUE ORDER BY username")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class)}).list()
                .stream()
                .filter(u -> factoryId == null || factoryId.equals(u[2]))
                .filter(u -> roles((String) u[1]).contains("TECHNICIAN") || roles((String) u[1]).contains("ENGINEER"))
                .map(u -> (String) u[0]).toList();
    }

    private static List<String> roles(String csv) {
        return csv == null ? List.of() : java.util.Arrays.stream(csv.split(",")).map(String::strip).toList();
    }

    /** Newest first, machines visible to the caller. */
    public List<MaintenanceRecord> recent(int limit) {
        VisibleMachines visible = machines.visible();
        if (visible.none()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM maintenance_records WHERE " + visible.condition("device_id")
                        + " ORDER BY COALESCE(completed_at, planned_at) DESC, id DESC LIMIT :l")
                .param("visibleIds", visible.parameter()).param("l", limit).query(MAPPER).list();
    }

    /**
     * Completes planned work. Engineers can complete any work order; a technician only the ones assigned to them.
     */
    @Transactional
    public MaintenanceRecord complete(long id, String notes, String actor, boolean isEngineer) {
        MaintenanceRecord current = get(id);
        if (!isEngineer && !actor.equals(current.assignedTo())) {
            throw new InvalidStateException("Only the assigned technician or an engineer can complete this work order");
        }
        return complete(id, notes, actor);
    }

    @Transactional
    public MaintenanceRecord complete(long id, String notes, String actor) {
        int updated = jdbc.sql("""
                UPDATE maintenance_records SET status = 'COMPLETED', completed_by = :by, completed_at = :at, notes = :notes
                WHERE id = :id AND status = 'PLANNED'""")
                .param("by", actor).param("at", utc(clock.instant())).param("notes", Text.truncate(notes, 2000))
                .param("id", id).update();
        if (updated != 1) {
            throw new InvalidStateException("Maintenance record " + id + " is not planned");
        }
        audit.record(actor, "MAINTENANCE_COMPLETED", "maintenance:" + id, Text.truncate(notes, 200));
        return get(id);
    }

    public MaintenanceRecord get(long id) {
        MaintenanceRecord record = jdbc.sql("SELECT * FROM maintenance_records WHERE id = :id").param("id", id)
                .query(MAPPER).optional()
                .orElseThrow(() -> new com.basintelligence.common.NotFoundException("Maintenance record not found: " + id));
        machines.checkAccess(record.machineId());
        return record;
    }

    /** Newest first. */
    public List<MaintenanceRecord> forMachine(UUID machineId, int limit) {
        return jdbc.sql("SELECT * FROM maintenance_records WHERE device_id = :d ORDER BY planned_at DESC, id DESC LIMIT :l")
                .param("d", machineId).param("l", limit).query(MAPPER).list();
    }

    public List<MaintenanceRecord> forRecommendations(List<Long> recommendationIds) {
        if (recommendationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM maintenance_records WHERE recommendation_id IN (:ids) ORDER BY id")
                .param("ids", recommendationIds).query(MAPPER).list();
    }
}

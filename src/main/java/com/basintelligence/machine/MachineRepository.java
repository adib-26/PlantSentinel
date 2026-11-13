package com.basintelligence.machine;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MachineRepository {

    private static final RowMapper<Machine> MAPPER = (rs, n) -> new Machine(
            rs.getObject("id", UUID.class),
            rs.getObject("factory_id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            MachineType.valueOf(rs.getString("device_type")),
            rs.getString("location"),
            HealthStatus.valueOf(rs.getString("health_status")),
            instant(rs, "last_seen_at"),
            instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public MachineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(UUID factoryId, String code, String name, MachineType type, String location, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO devices (id, factory_id, code, name, device_type, location, health_status, created_at)
                VALUES (:id, :factoryId, :code, :name, :type, :location, 'UNKNOWN', :createdAt)""")
                .param("id", id)
                .param("factoryId", factoryId)
                .param("code", code)
                .param("name", name)
                .param("type", type.name())
                .param("location", location)
                .param("createdAt", utc(createdAt))
                .update();
        return id;
    }

    public List<Machine> findAll() {
        return jdbc.sql("SELECT * FROM devices ORDER BY code").query(MAPPER).list();
    }

    public List<Machine> findByFactory(UUID factoryId) {
        return jdbc.sql("SELECT * FROM devices WHERE factory_id = :f ORDER BY code").param("f", factoryId).query(MAPPER).list();
    }

    /** Machines assigned to a technician directly, or through a work order assigned to them, within one factory. */
    public List<Machine> findAssignedTo(String username, UUID factoryId) {
        return jdbc.sql("""
                SELECT * FROM devices WHERE factory_id = :f AND (
                    id IN (SELECT device_id FROM machine_assignments WHERE username = :u)
                    OR id IN (SELECT device_id FROM maintenance_records WHERE assigned_to = :u))
                ORDER BY code""")
                .param("u", username).param("f", factoryId).query(MAPPER).list();
    }

    public boolean isAssignedTo(UUID machineId, String username) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM devices WHERE id = :id AND (
                    id IN (SELECT device_id FROM machine_assignments WHERE username = :u)
                    OR id IN (SELECT device_id FROM maintenance_records WHERE assigned_to = :u))""")
                .param("id", machineId).param("u", username).query(Long.class).single() > 0;
    }

    public record Assignment(String machineCode, String username, String assignedBy, Instant assignedAt) {
    }

    /** @return false if the assignment already existed */
    public boolean insertAssignment(UUID machineId, String username, String by, Instant at) {
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM machine_assignments WHERE device_id = :d AND username = :u")
                .param("d", machineId).param("u", username).query(Long.class).single() > 0;
        if (exists) {
            return false;
        }
        jdbc.sql("""
                INSERT INTO machine_assignments (device_id, username, assigned_by, assigned_at)
                VALUES (:d, :u, :by, :at)""")
                .param("d", machineId).param("u", username).param("by", by).param("at", utc(at)).update();
        return true;
    }

    public boolean deleteAssignment(UUID machineId, String username) {
        return jdbc.sql("DELETE FROM machine_assignments WHERE device_id = :d AND username = :u")
                .param("d", machineId).param("u", username).update() > 0;
    }

    public List<Assignment> findAssignments() {
        return jdbc.sql("""
                SELECT d.code, a.username, a.assigned_by, a.assigned_at
                FROM machine_assignments a JOIN devices d ON d.id = a.device_id ORDER BY d.code, a.username""")
                .query((rs, n) -> new Assignment(rs.getString(1), rs.getString(2), rs.getString(3), instant(rs, 4)))
                .list();
    }

    public Optional<Machine> findById(UUID id) {
        return jdbc.sql("SELECT * FROM devices WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<Machine> findByCode(String code) {
        return jdbc.sql("SELECT * FROM devices WHERE code = :code").param("code", code).query(MAPPER).optional();
    }

    public boolean existsByCode(String code) {
        return jdbc.sql("SELECT COUNT(*) FROM devices WHERE code = :code").param("code", code)
                .query(Long.class).single() > 0;
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM devices").query(Long.class).single();
    }

    public void markEdgeManaged(UUID id) {
        jdbc.sql("UPDATE devices SET edge_managed = TRUE WHERE id = :id AND edge_managed = FALSE").param("id", id).update();
    }

    public java.util.Set<UUID> edgeManagedIds() {
        return new java.util.HashSet<>(jdbc.sql("SELECT id FROM devices WHERE edge_managed = TRUE")
                .query((rs, n) -> rs.getObject(1, UUID.class)).list());
    }

    public void updateHealth(UUID id, HealthStatus status, Instant lastSeenAt) {
        jdbc.sql("UPDATE devices SET health_status = :status, last_seen_at = :seen WHERE id = :id")
                .param("status", status.name())
                .param("seen", utc(lastSeenAt))
                .param("id", id)
                .update();
    }
}

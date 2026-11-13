package com.basintelligence.failure;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.agent.BasMcpTools;
import com.basintelligence.common.AuditService;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.Text;
import com.basintelligence.investigation.InvestigationService;

/**
 * Confirmed machine failures. A failure is only recorded when a person who inspected the machine confirms it,
 * typically while completing a work order. A high predicted risk, an anomaly or a sensor fault never creates one.
 * Each failure links to the last prediction made before it, so predictions can later be checked against reality.
 */
@Service
public class FailureEventService {

    public enum FailureMode {
        BEARING, COOLING, OVERLOAD, LEAK, ELECTRICAL, STRUCTURAL_CRACK, OTHER
    }

    public record FailureEvent(long id, UUID machineId, Instant occurredAt, FailureMode mode, String description,
            String confirmedBy, Instant confirmedAt, Long maintenanceRecordId, Long investigationId,
            Long priorPredictionId, Double priorProbability) {
    }

    /** Automated actors may predict and propose, never confirm. Also enforced by a CHECK constraint. */
    static final Set<String> AUTOMATED = Set.of(BasMcpTools.AGENT, InvestigationService.SYSTEM_ACTOR, "system");

    private static final RowMapper<FailureEvent> MAPPER = (rs, n) -> new FailureEvent(rs.getLong("id"),
            rs.getObject("device_id", UUID.class), instant(rs, "occurred_at"), FailureMode.valueOf(rs.getString("failure_mode")),
            rs.getString("description"), rs.getString("confirmed_by"), instant(rs, "confirmed_at"),
            rs.getObject("maintenance_record_id", Long.class), rs.getObject("investigation_id", Long.class),
            rs.getObject("prior_prediction_id", Long.class), rs.getObject("prior_probability", Double.class));

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final Clock clock;

    public FailureEventService(JdbcClient jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public FailureEvent confirm(UUID machineId, FailureMode mode, String description, Instant occurredAt,
            Long maintenanceRecordId, String actor) {
        if (actor == null || AUTOMATED.contains(actor)) {
            throw new InvalidStateException("Only a person who inspected the machine can confirm a failure");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Describe what was found");
        }
        Instant now = clock.instant();
        Instant at = occurredAt == null ? now : occurredAt;
        if (at.isAfter(now)) {
            throw new IllegalArgumentException("A failure cannot be confirmed in the future");
        }
        Long prior = jdbc.sql("""
                SELECT id FROM model_predictions WHERE device_id = :d AND predicted_at <= :at
                ORDER BY predicted_at DESC, id DESC LIMIT 1""").param("d", machineId).param("at", utc(at))
                .query(Long.class).optional().orElse(null);
        Long investigation = maintenanceRecordId == null ? null : jdbc.sql("""
                SELECT r.investigation_id FROM maintenance_records m JOIN recommendations r ON r.id = m.recommendation_id
                WHERE m.id = :m""").param("m", maintenanceRecordId).query(Long.class).optional().orElse(null);
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO failure_events (device_id, occurred_at, failure_mode, description, confirmed_by, confirmed_at,
                                            maintenance_record_id, investigation_id, prior_prediction_id)
                VALUES (:d, :at, :mode, :desc, :by, :now, :m, :i, :p)""")
                .param("d", machineId).param("at", utc(at)).param("mode", mode.name())
                .param("desc", Text.truncate(description.strip(), 2000)).param("by", actor).param("now", utc(now))
                .param("m", maintenanceRecordId).param("i", investigation).param("p", prior)
                .update(keys, "id");
        long id = keys.getKey().longValue();
        audit.record(actor, "FAILURE_CONFIRMED", "failure:" + id, mode + ": " + Text.truncate(description, 200));
        return get(id);
    }

    public FailureEvent get(long id) {
        return jdbc.sql(SELECT + " WHERE f.id = :id").param("id", id).query(MAPPER).single();
    }

    /** Newest first. */
    public List<FailureEvent> forMachine(UUID machineId, int limit) {
        return jdbc.sql(SELECT + " WHERE f.device_id = :d ORDER BY f.occurred_at DESC, f.id DESC LIMIT :l")
                .param("d", machineId).param("l", limit).query(MAPPER).list();
    }

    private static final String SELECT = """
            SELECT f.*, p.probability AS prior_probability FROM failure_events f
            LEFT JOIN model_predictions p ON p.id = f.prior_prediction_id""";
}

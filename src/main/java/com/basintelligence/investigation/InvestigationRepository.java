package com.basintelligence.investigation;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.basintelligence.investigation.InvestigationModels.Evidence;
import com.basintelligence.investigation.InvestigationModels.EvidenceKind;
import com.basintelligence.investigation.InvestigationModels.IncidentReport;
import com.basintelligence.investigation.InvestigationModels.Investigation;
import com.basintelligence.investigation.InvestigationModels.Priority;
import com.basintelligence.investigation.InvestigationModels.Recommendation;
import com.basintelligence.investigation.InvestigationModels.RecommendationStatus;
import com.basintelligence.investigation.InvestigationModels.Status;
import com.basintelligence.risk.RiskLevel;

@Repository
public class InvestigationRepository {

    private static final RowMapper<Investigation> INVESTIGATION = (rs, n) -> new Investigation(
            rs.getLong("id"),
            rs.getObject("device_id", UUID.class),
            rs.getString("question"),
            Status.valueOf(rs.getString("status")),
            (Double) rs.getObject("risk_score"),
            rs.getString("risk_level") == null ? null : RiskLevel.valueOf(rs.getString("risk_level")),
            rs.getString("diagnosis"),
            rs.getString("explanation"),
            rs.getString("requested_by"),
            instant(rs, "created_at"),
            instant(rs, "closed_at"),
            rs.getObject("prediction_id", Long.class),
            rs.getObject("created_via_tool_call", Long.class));

    private static final RowMapper<Evidence> EVIDENCE = (rs, n) -> new Evidence(
            rs.getString("ref_label"),
            EvidenceKind.valueOf(rs.getString("kind")),
            rs.getString("title"),
            rs.getString("detail"),
            rs.getString("source"),
            rs.getObject("document_chunk_id", Long.class),
            rs.getObject("document_id", Long.class));

    private static final String RECOMMENDATION_SELECT = """
            SELECT r.*, a.decided_by, a.decided_at, a.comment
            FROM recommendations r LEFT JOIN approvals a ON a.recommendation_id = r.id
            """;

    private static final RowMapper<Recommendation> RECOMMENDATION = (rs, n) -> new Recommendation(
            rs.getLong("id"),
            rs.getLong("investigation_id"),
            rs.getString("action"),
            rs.getString("rationale"),
            Priority.valueOf(rs.getString("priority")),
            RecommendationStatus.valueOf(rs.getString("status")),
            rs.getString("proposed_by"),
            instant(rs, "proposed_at"),
            rs.getString("decided_by"),
            instant(rs, "decided_at"),
            rs.getString("comment"),
            rs.getObject("proposed_via_tool_call", Long.class));

    private static final RowMapper<IncidentReport> REPORT = (rs, n) -> new IncidentReport(
            rs.getLong("id"),
            rs.getLong("investigation_id"),
            rs.getString("title"),
            rs.getString("body_markdown"),
            rs.getString("generated_by"),
            instant(rs, "generated_at"));

    private final JdbcClient jdbc;

    public InvestigationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(Investigation i) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO investigations (device_id, prediction_id, question, status, risk_score, risk_level, diagnosis,
                                            explanation, requested_by, created_at, created_via_tool_call)
                VALUES (:deviceId, :predictionId, :question, :status, :riskScore, :riskLevel, :diagnosis, :explanation, :by, :at,
                        :toolCall)""")
                .param("toolCall", i.createdViaToolCall())
                .param("deviceId", i.machineId())
                .param("predictionId", i.predictionId())
                .param("question", i.question())
                .param("status", i.status().name())
                .param("riskScore", i.riskScore())
                .param("riskLevel", i.riskLevel() == null ? null : i.riskLevel().name())
                .param("diagnosis", i.diagnosis())
                .param("explanation", i.explanation())
                .param("by", i.requestedBy())
                .param("at", utc(i.createdAt()))
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public Optional<Investigation> findById(long id) {
        return jdbc.sql("SELECT * FROM investigations WHERE id = :id").param("id", id).query(INVESTIGATION).optional();
    }

    public List<Investigation> findRecent(int limit, com.basintelligence.machine.VisibleMachines visible) {
        if (visible.none()) {
            return List.of();
        }
        return jdbc.sql("SELECT * FROM investigations WHERE " + visible.condition("device_id")
                + " ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("visibleIds", visible.parameter()).param("limit", limit).query(INVESTIGATION).list();
    }

    public List<Investigation> findByMachine(UUID machineId, int limit) {
        return jdbc.sql("SELECT * FROM investigations WHERE device_id = :m ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("m", machineId).param("limit", limit).query(INVESTIGATION).list();
    }

    public void updateStatus(long id, Status status, Instant closedAt) {
        jdbc.sql("UPDATE investigations SET status = :status, closed_at = :closedAt WHERE id = :id")
                .param("status", status.name())
                .param("closedAt", utc(closedAt))
                .param("id", id)
                .update();
    }

    public void insertEvidence(long investigationId, Evidence e) {
        jdbc.sql("""
                INSERT INTO investigation_evidence (investigation_id, ref_label, kind, title, detail, source, document_chunk_id)
                VALUES (:id, :label, :kind, :title, :detail, :source, :chunk)""")
                .param("chunk", e.documentChunkId())
                .param("id", investigationId)
                .param("label", e.refLabel())
                .param("kind", e.kind().name())
                .param("title", e.title())
                .param("detail", e.detail())
                .param("source", e.source())
                .update();
    }

    public List<Evidence> findEvidence(long investigationId) {
        return jdbc.sql("""
                SELECT e.*, c.document_id FROM investigation_evidence e
                LEFT JOIN document_chunks c ON c.id = e.document_chunk_id
                WHERE e.investigation_id = :id ORDER BY e.id""")
                .param("id", investigationId).query(EVIDENCE).list();
    }

    public long insertRecommendation(long investigationId, String action, String rationale, Priority priority,
            String proposedBy, Instant at, Long toolCallId) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO recommendations (investigation_id, action, rationale, priority, status, proposed_by, proposed_at,
                                             proposed_via_tool_call)
                VALUES (:id, :action, :rationale, :priority, 'PROPOSED', :by, :at, :toolCall)""")
                .param("id", investigationId)
                .param("action", action)
                .param("rationale", rationale)
                .param("priority", priority.name())
                .param("by", proposedBy)
                .param("at", utc(at))
                .param("toolCall", toolCallId)
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public Optional<Recommendation> findRecommendation(long id) {
        return jdbc.sql(RECOMMENDATION_SELECT + " WHERE r.id = :id").param("id", id).query(RECOMMENDATION).optional();
    }

    public List<Recommendation> findRecommendations(long investigationId) {
        return jdbc.sql(RECOMMENDATION_SELECT + " WHERE r.investigation_id = :id ORDER BY r.id")
                .param("id", investigationId).query(RECOMMENDATION).list();
    }

    /**
     * Records a human decision. The conditional update stops a second review; the approvals table's unique key and
     * four-eyes check constraint enforce the same rules again at the database level.
     */
    public boolean review(long id, RecommendationStatus decision, String proposedBy, String reviewer, String comment,
            Instant at) {
        int updated = jdbc.sql("UPDATE recommendations SET status = :status WHERE id = :id AND status = 'PROPOSED'")
                .param("status", decision.name())
                .param("id", id)
                .update();
        if (updated != 1) {
            return false;
        }
        jdbc.sql("""
                INSERT INTO approvals (recommendation_id, decision, proposed_by, decided_by, decided_at, comment)
                VALUES (:id, :decision, :proposedBy, :by, :at, :comment)""")
                .param("id", id)
                .param("decision", decision.name())
                .param("proposedBy", proposedBy)
                .param("by", reviewer)
                .param("at", utc(at))
                .param("comment", comment)
                .update();
        return true;
    }

    /** One row of the maintenance screen: a recommendation with its investigation and machine. */
    public record RecommendationRow(Recommendation recommendation, long investigationId, String machineCode,
            String diagnosis) {
    }

    public List<RecommendationRow> findRecommendationRows(boolean pendingOnly, int limit,
            com.basintelligence.machine.VisibleMachines visible) {
        if (visible.none()) {
            return List.of();
        }
        return jdbc.sql(("""
                SELECT r.*, a.decided_by, a.decided_at, a.comment, d.code AS machine_code, i.diagnosis AS inv_diagnosis
                FROM recommendations r
                JOIN investigations i ON i.id = r.investigation_id
                JOIN devices d ON d.id = i.device_id
                LEFT JOIN approvals a ON a.recommendation_id = r.id
                WHERE\s""" + visible.condition("i.device_id") + (pendingOnly ? " AND r.status = 'PROPOSED' " : " ")
                + "ORDER BY r.proposed_at DESC, r.id DESC LIMIT :limit"))
                .param("visibleIds", visible.parameter())
                .param("limit", limit)
                .query((rs, n) -> new RecommendationRow(RECOMMENDATION.mapRow(rs, n), rs.getLong("investigation_id"),
                        rs.getString("machine_code"), rs.getString("inv_diagnosis")))
                .list();
    }

    public record ApprovalRow(long recommendationId, long investigationId, String machineCode, String action,
            String decision, String proposedBy, String decidedBy, Instant decidedAt, String comment) {
    }

    public List<ApprovalRow> findApprovals(int limit, com.basintelligence.machine.VisibleMachines visible) {
        if (visible.none()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT a.*, r.action, r.investigation_id, d.code AS machine_code
                FROM approvals a JOIN recommendations r ON r.id = a.recommendation_id
                JOIN investigations i ON i.id = r.investigation_id JOIN devices d ON d.id = i.device_id
                WHERE\s""" + visible.condition("i.device_id") + " ORDER BY a.decided_at DESC, a.id DESC LIMIT :limit")
                .param("visibleIds", visible.parameter()).param("limit", limit)
                .query((rs, n) -> new ApprovalRow(rs.getLong("recommendation_id"), rs.getLong("investigation_id"),
                        rs.getString("machine_code"), rs.getString("action"), rs.getString("decision"),
                        rs.getString("proposed_by"), rs.getString("decided_by"), instant(rs, "decided_at"),
                        rs.getString("comment")))
                .list();
    }

    public record ReportRow(long investigationId, String machineCode, String title, String generatedBy, Instant generatedAt) {
    }

    public List<ReportRow> findReports(int limit, com.basintelligence.machine.VisibleMachines visible) {
        if (visible.none()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT r.investigation_id, r.title, r.generated_by, r.generated_at, d.code AS machine_code
                FROM incident_reports r JOIN investigations i ON i.id = r.investigation_id JOIN devices d ON d.id = i.device_id
                WHERE\s""" + visible.condition("i.device_id") + " ORDER BY r.generated_at DESC LIMIT :limit")
                .param("visibleIds", visible.parameter()).param("limit", limit)
                .query((rs, n) -> new ReportRow(rs.getLong("investigation_id"), rs.getString("machine_code"),
                        rs.getString("title"), rs.getString("generated_by"), instant(rs, "generated_at")))
                .list();
    }

    public long countPendingForMachine(UUID machineId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM recommendations r JOIN investigations i ON i.id = r.investigation_id
                WHERE i.device_id = :d AND r.status = 'PROPOSED'""").param("d", machineId).query(Long.class).single();
    }

    public long countPending(com.basintelligence.machine.VisibleMachines visible) {
        if (visible.none()) {
            return 0;
        }
        return jdbc.sql("""
                SELECT COUNT(*) FROM recommendations r JOIN investigations i ON i.id = r.investigation_id
                WHERE r.status = 'PROPOSED' AND\s""" + visible.condition("i.device_id"))
                .param("visibleIds", visible.parameter()).query(Long.class).single();
    }

    public void insertReport(long investigationId, String title, String body, String by, Instant at) {
        jdbc.sql("""
                INSERT INTO incident_reports (investigation_id, title, body_markdown, generated_by, generated_at)
                VALUES (:id, :title, :body, :by, :at)""")
                .param("id", investigationId)
                .param("title", title)
                .param("body", body)
                .param("by", by)
                .param("at", utc(at))
                .update();
    }

    public Optional<IncidentReport> findReport(long investigationId) {
        return jdbc.sql("SELECT * FROM incident_reports WHERE investigation_id = :id")
                .param("id", investigationId).query(REPORT).optional();
    }
}

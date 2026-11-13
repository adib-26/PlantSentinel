package com.basintelligence.investigation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.basintelligence.machine.Machine;
import com.basintelligence.maintenance.MaintenanceService.MaintenanceRecord;
import com.basintelligence.risk.RiskLevel;

/** Domain types of the investigation workflow. */
public final class InvestigationModels {

    private InvestigationModels() {
    }

    /** OPEN: awaiting human review of its recommendations. REVIEWED: all decided. CLOSED: report issued. */
    public enum Status {
        OPEN, REVIEWED, CLOSED
    }

    public enum EvidenceKind {
        TELEMETRY(EvidenceClass.MEASURED),
        ANOMALY(EvidenceClass.RULE_BASED),
        MULTIVARIATE(EvidenceClass.MODEL_PREDICTION),
        RISK(EvidenceClass.MODEL_PREDICTION),
        INSPECTION(EvidenceClass.MODEL_PREDICTION),
        MANUAL(EvidenceClass.DOCUMENTED),
        AGENT_NOTE(EvidenceClass.AI_INTERPRETATION);

        private final EvidenceClass evidenceClass;

        EvidenceKind(EvidenceClass evidenceClass) {
            this.evidenceClass = evidenceClass;
        }

        public EvidenceClass evidenceClass() {
            return evidenceClass;
        }
    }

    public enum Priority {
        LOW, MEDIUM, HIGH, URGENT
    }

    public enum RecommendationStatus {
        PROPOSED, APPROVED, REJECTED
    }

    public record Investigation(long id, UUID machineId, String question, Status status, Double riskScore,
            RiskLevel riskLevel, String diagnosis, String explanation, String requestedBy, Instant createdAt,
            Instant closedAt, Long predictionId, Long createdViaToolCall) {

        public Investigation(long id, UUID machineId, String question, Status status, Double riskScore,
                RiskLevel riskLevel, String diagnosis, String explanation, String requestedBy, Instant createdAt,
                Instant closedAt, Long predictionId) {
            this(id, machineId, question, status, riskScore, riskLevel, diagnosis, explanation, requestedBy, createdAt,
                    closedAt, predictionId, null);
        }
    }

    /** {@code documentChunkId}/{@code documentId} are set for MANUAL evidence so the UI can open the exact section. */
    public record Evidence(String refLabel, EvidenceKind kind, String title, String detail, String source,
            Long documentChunkId, Long documentId) {

        public Evidence(String refLabel, EvidenceKind kind, String title, String detail, String source) {
            this(refLabel, kind, title, detail, source, null, null);
        }
    }

    /** Review fields come from the {@code approvals} table; {@code proposedViaToolCall} links to {@code agent_tool_audit}. */
    public record Recommendation(long id, long investigationId, String action, String rationale, Priority priority,
            RecommendationStatus status, String proposedBy, Instant proposedAt, String reviewedBy, Instant reviewedAt,
            String reviewComment, Long proposedViaToolCall) {
    }

    public record IncidentReport(long id, long investigationId, String title, String bodyMarkdown, String generatedBy,
            Instant generatedAt) {
    }

    /** Everything needed to display or export an investigation. */
    public record InvestigationView(Investigation investigation, Machine machine, List<Evidence> evidence,
            List<Recommendation> recommendations, List<MaintenanceRecord> maintenance, Optional<IncidentReport> report) {
    }
}

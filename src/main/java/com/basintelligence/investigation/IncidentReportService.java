package com.basintelligence.investigation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.Text;
import com.basintelligence.investigation.InvestigationModels.Evidence;
import com.basintelligence.investigation.InvestigationModels.IncidentReport;
import com.basintelligence.investigation.InvestigationModels.Investigation;
import com.basintelligence.investigation.InvestigationModels.InvestigationView;
import com.basintelligence.investigation.InvestigationModels.Recommendation;
import com.basintelligence.investigation.InvestigationModels.RecommendationStatus;
import com.basintelligence.investigation.InvestigationModels.Status;
import com.basintelligence.machine.Machine;

/** Produces the Markdown incident report once every recommendation has a human decision. */
@Service
public class IncidentReportService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final InvestigationService investigations;
    private final InvestigationRepository repository;
    private final AuditService audit;
    private final Clock clock;

    public IncidentReportService(InvestigationService investigations, InvestigationRepository repository,
            AuditService audit, Clock clock) {
        this.investigations = investigations;
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IncidentReport generate(long investigationId, String actor) {
        InvestigationView view = investigations.view(investigationId);
        Investigation inv = view.investigation();
        if (inv.status() == Status.CLOSED) {
            return view.report().orElseThrow();
        }
        if (inv.status() != Status.REVIEWED) {
            throw new InvalidStateException("Every recommendation must be approved or rejected before the report is issued");
        }
        Instant now = clock.instant();
        Machine m = view.machine();
        String title = "Incident report INV-%d: %s %s".formatted(inv.id(), m.code(), inv.diagnosis());
        repository.insertReport(inv.id(), Text.truncate(title, 255), render(view, title, actor, now), actor, now);
        repository.updateStatus(inv.id(), Status.CLOSED, now);
        audit.record(actor, "REPORT_GENERATED", "investigation:" + inv.id(), title);
        return repository.findReport(inv.id()).orElseThrow();
    }

    static String render(InvestigationView view, String title, String actor, Instant now) {
        Investigation inv = view.investigation();
        Machine m = view.machine();
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title).append("\n\n");
        md.append("| Field | Value |\n|---|---|\n");
        row(md, "Machine", m.code() + " · " + m.name() + " (" + m.type().displayName() + ")");
        row(md, "Location", m.location() == null ? "–" : m.location());
        row(md, "Investigation opened", TS.format(inv.createdAt()) + " by " + inv.requestedBy());
        row(md, "Report issued", TS.format(now) + " by " + actor);
        row(md, "Diagnosis", inv.diagnosis());
        row(md, "Failure risk", inv.riskLevel() == null ? "n/a" : inv.riskLevel() + " (" + Text.pct(inv.riskScore()) + ")");
        if (inv.question() != null && !inv.question().isBlank()) {
            row(md, "Question", inv.question());
        }
        md.append("\n## Findings\n\n").append(inv.explanation()).append("\n\n");

        md.append("## Maintenance decisions\n\n");
        for (Recommendation r : view.recommendations()) {
            md.append("### ").append(r.status()).append(" · ").append(r.priority()).append(" priority\n\n");
            md.append("**Action:** ").append(r.action()).append("\n\n");
            md.append("**Rationale:** ").append(r.rationale()).append("\n\n");
            md.append("Proposed by `").append(r.proposedBy()).append("` at ").append(TS.format(r.proposedAt()));
            if (r.reviewedBy() != null) {
                md.append(". ").append(r.status() == RecommendationStatus.APPROVED ? "Approved" : "Rejected").append(" by `")
                        .append(r.reviewedBy()).append("` at ").append(TS.format(r.reviewedAt()));
                if (r.reviewComment() != null && !r.reviewComment().isBlank()) {
                    md.append(": \"").append(r.reviewComment()).append('"');
                }
            }
            md.append(".\n\n");
        }

        if (!view.maintenance().isEmpty()) {
            md.append("## Planned maintenance\n\n");
            for (var rec : view.maintenance()) {
                md.append("- **").append(rec.status()).append("** (").append(rec.priority()).append("): ").append(rec.action())
                        .append(" Planned by `").append(rec.plannedBy()).append("` at ").append(TS.format(rec.plannedAt()));
                if (rec.completedAt() != null) {
                    md.append("; completed by `").append(rec.completedBy()).append("` at ").append(TS.format(rec.completedAt()));
                }
                md.append(".\n");
            }
            md.append("\n");
        }

        md.append("## Evidence\n\n");
        for (Evidence e : view.evidence()) {
            md.append("**[").append(e.refLabel()).append("] ").append(e.title()).append("** (")
                    .append(e.kind().evidenceClass().label()).append(" · ").append(e.kind());
            if (e.source() != null) {
                md.append(", ").append(e.source());
            }
            md.append(")\n\n> ").append(e.detail().replace("\n", "\n> ")).append("\n\n");
        }
        md.append("---\nGenerated by BAS Intelligence. Analytics: envelope and z-score detectors, Smile Isolation Forest "
                + "and logistic regression. Vision: OpenCV with ONNX Runtime. Retrieval over the maintenance manual library. Agent: Kiro via MCP (proposals only).\n");
        return md.toString();
    }

    private static void row(StringBuilder md, String key, String value) {
        md.append("| ").append(key).append(" | ").append(value.replace("|", "\\|")).append(" |\n");
    }
}

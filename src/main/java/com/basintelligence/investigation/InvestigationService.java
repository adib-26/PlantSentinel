package com.basintelligence.investigation;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.analytics.AnomalyEvent;
import com.basintelligence.analytics.TelemetryAnalysis;
import com.basintelligence.analytics.TelemetryAnalysis.MetricSummary;
import com.basintelligence.common.AuditService;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.NotFoundException;
import com.basintelligence.common.Text;
import com.basintelligence.config.BasProperties;
import com.basintelligence.investigation.DiagnosisEngine.Diagnosis;
import com.basintelligence.investigation.DiagnosisEngine.Hypothesis;
import com.basintelligence.investigation.InvestigationModels.Evidence;
import com.basintelligence.investigation.InvestigationModels.EvidenceKind;
import com.basintelligence.investigation.InvestigationModels.Investigation;
import com.basintelligence.investigation.InvestigationModels.InvestigationView;
import com.basintelligence.investigation.InvestigationModels.Priority;
import com.basintelligence.investigation.InvestigationModels.Recommendation;
import com.basintelligence.investigation.InvestigationModels.RecommendationStatus;
import com.basintelligence.investigation.InvestigationModels.Status;
import com.basintelligence.knowledge.ManualService;
import com.basintelligence.knowledge.ManualHit;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.Metric;
import com.basintelligence.machine.OperatingLimits;
import com.basintelligence.machine.Severity;
import com.basintelligence.risk.RiskAssessment;
import com.basintelligence.risk.PredictionService;
import com.basintelligence.risk.RiskService;
import com.basintelligence.maintenance.MaintenanceService;
import com.basintelligence.agent.AgentCallContext;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.vision.Inspection;
import com.basintelligence.vision.InspectionService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Runs a complete investigation of one machine: gather telemetry, anomalies, risk and inspections; diagnose;
 * retrieve manual evidence (RAG); write a cited explanation; and draft a recommendation that a human must
 * approve or reject.
 */
@Service
public class InvestigationService {

    /** Name recorded for proposals made by the built-in diagnosis pipeline. */
    public static final String SYSTEM_ACTOR = "bas-diagnostics";
    private static final Duration INSPECTION_LOOKBACK = Duration.ofDays(7);
    private static final int MANUAL_HITS = 3;
    /** Alternative hypotheses at or above this confidence also get a drafted recommendation. */
    static final double SECONDARY_CONFIDENCE = 0.6;

    private final MachineService machines;
    private final TelemetryService telemetry;
    private final RiskService risk;
    private final InspectionService inspections;
    private final ManualService manuals;
    private final InvestigationRepository repository;
    private final AuditService audit;
    private final BasProperties properties;
    private final MeterRegistry meters;
    private final PredictionService predictions;
    private final MaintenanceService maintenance;
    private final Clock clock;
    private final DiagnosisEngine engine = new DiagnosisEngine();

    public InvestigationService(MachineService machines, TelemetryService telemetry, RiskService risk,
            InspectionService inspections, ManualService manuals, InvestigationRepository repository, AuditService audit,
            BasProperties properties, MeterRegistry meters, PredictionService predictions, MaintenanceService maintenance,
            Clock clock) {
        this.predictions = predictions;
        this.maintenance = maintenance;
        this.machines = machines;
        this.telemetry = telemetry;
        this.risk = risk;
        this.inspections = inspections;
        this.manuals = manuals;
        this.repository = repository;
        this.audit = audit;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    @Transactional
    public InvestigationView investigate(java.util.UUID machineId, String question, String actor) {
        return investigate(machineId, question, actor, true);
    }

    /**
     * @param draftRecommendations when false (agent-driven reports), no recommendation is drafted automatically;
     *                             the agent prepares one explicitly with {@link #propose}, so a human sees exactly one
     *                             approval request per agent proposal
     */
    @Transactional
    public InvestigationView investigate(java.util.UUID machineId, String question, String actor,
            boolean draftRecommendations) {
        Timer.Sample timer = Timer.start(meters);
        Machine machine = machines.get(machineId);
        Duration window = properties.telemetry().analysisWindow();
        TelemetryAnalysis analysis = telemetry.analyze(machine, window);
        if (!analysis.hasData()) {
            throw new InvalidStateException("No telemetry for " + machine.code() + " in the last " + window.toMinutes()
                    + " minutes; nothing to investigate yet");
        }
        List<AnomalyEvent> anomalies = telemetry.anomalies(machine.id(), window, 100);
        Optional<RiskAssessment> assessment = risk.assess(machine);
        List<Inspection> recentInspections = inspections.recent(machine.id(), INSPECTION_LOOKBACK, 5);

        Diagnosis diagnosis = engine.diagnose(new DiagnosisEngine.Input(analysis, anomalies, assessment, recentInspections));
        Hypothesis primary = diagnosis.primary();
        // The primary hypothesis plus one strong alternative each get their own retrieval and recommendation.
        List<Hypothesis> actionable = new ArrayList<>(List.of(primary));
        diagnosis.hypotheses().stream().skip(1).filter(h -> h.confidence() >= SECONDARY_CONFIDENCE).limit(1)
                .forEach(actionable::add);
        Map<String, List<ManualHit>> hitsByHypothesis = new LinkedHashMap<>();
        for (Hypothesis h : actionable) {
            String query = h.manualQuery() + " " + machine.type().displayName() + (question == null ? "" : " " + question);
            hitsByHypothesis.put(h.code(), manuals.search(query, machine.type(), h == primary ? MANUAL_HITS : 2));
        }

        EvidenceBuilder evidence = new EvidenceBuilder();
        Map<Metric, String> metricRefs = telemetryEvidence(machine, analysis, evidence);
        String multivariateRef = null;
        if (analysis.multivariate() != null) {
            var mv = analysis.multivariate();
            multivariateRef = evidence.add(EvidenceKind.MULTIVARIATE, "Isolation Forest (multivariate)",
                    "Anomaly score %s vs adaptive threshold %s (99th percentile of %d earlier readings in the window): %s."
                            .formatted(Text.num(mv.score(), 3), Text.num(mv.threshold(), 3), mv.trainingSamples(),
                                    mv.anomalous() ? "ABNORMAL operating state" : "within normal variation"),
                    "Smile IsolationForest");
        }
        String anomalyRef = anomalies.isEmpty() ? null
                : evidence.add(EvidenceKind.ANOMALY, anomalies.size() + " anomaly event(s) in the last " + window.toMinutes() + " min",
                        summariseAnomalies(anomalies), "Streaming detectors (envelope + z-score)");
        String riskRef = assessment.map(r -> evidence.add(EvidenceKind.RISK,
                "Failure risk " + r.level() + " (" + Text.pct(r.probability()) + ")", riskDetail(r), r.model().algorithm()))
                .orElse(null);
        List<String> inspectionRefs = recentInspections.stream()
                .map(i -> evidence.add(EvidenceKind.INSPECTION, "Visual inspection #" + i.id() + ": " + i.component(),
                        "%s, defect probability %s, %d region(s), %s, inspected by %s."
                                .formatted(i.verdict(), Text.pct(i.defectProbability()), i.defectRegions(), i.inspectedAt(),
                                        i.inspectedBy()),
                        "ONNX model " + i.modelVersion()))
                .toList();
        Map<Long, String> manualRefByChunk = new LinkedHashMap<>();
        List<ManualHit> hits = new ArrayList<>();
        for (List<ManualHit> list : hitsByHypothesis.values()) {
            for (ManualHit h : list) {
                manualRefByChunk.computeIfAbsent(h.chunkId(), id -> {
                    hits.add(h);
                    return evidence.add(EvidenceKind.MANUAL, h.citation(), Text.truncate(h.content(), 900),
                            manuals.retrieverName() + ", score " + Text.num(h.score(), 3), h.chunkId());
                });
            }
        }
        List<String> manualRefs = hits.stream().map(h -> manualRefByChunk.get(h.chunkId())).toList();

        String explanation = explain(machine, diagnosis, assessment, metricRefs, multivariateRef, anomalyRef, riskRef,
                inspectionRefs, hits, manualRefs);
        String diagnosisLine = primary.title() + " (confidence " + Text.pct(primary.confidence()) + ")";

        Long predictionId = assessment.map(a -> predictions.record(machine, a, PredictionService.Trigger.INVESTIGATION, null))
                .orElse(null);
        long id = repository.insert(new Investigation(0, machine.id(), Text.truncate(question, 1000), Status.OPEN,
                assessment.map(RiskAssessment::probability).orElse(null),
                assessment.map(RiskAssessment::level).orElse(null), diagnosisLine, explanation, actor, clock.instant(),
                null, predictionId,
                com.basintelligence.agent.BasMcpTools.AGENT.equals(actor) ? AgentCallContext.currentToolCall() : null));
        evidence.items().forEach(e -> repository.insertEvidence(id, e));

        for (Hypothesis h : draftRecommendations ? actionable : List.<Hypothesis>of()) {
            List<String> refs = new ArrayList<>();
            h.metrics().stream().map(metricRefs::get).filter(r -> r != null).forEach(refs::add);
            if (h.usesInspection()) {
                refs.addAll(inspectionRefs);
            }
            if (riskRef != null) {
                refs.add(riskRef);
            }
            hitsByHypothesis.get(h.code()).forEach(hit -> refs.add(manualRefByChunk.get(hit.chunkId())));
            String label = h == primary ? "Primary diagnosis" : "Secondary finding";
            repository.insertRecommendation(id, h.action(),
                    label + ": " + h.title() + " (confidence " + Text.pct(h.confidence()) + "). Supported by "
                            + String.join(", ", refs.stream().distinct().toList())
                            + ". Priority derived from current envelope severity and failure risk.",
                    diagnosis.priority(), SYSTEM_ACTOR, clock.instant(), null);
        }

        audit.record(actor, "INVESTIGATION_STARTED", "investigation:" + id, machine.code() + ": " + diagnosisLine);
        timer.stop(meters.timer("bas.investigation.duration"));
        meters.counter("bas.investigations", "diagnosis", primary.code()).increment();
        return view(id);
    }

    /** Maximum open proposals the agent may place on one investigation, so it cannot flood the review queue. */
    static final int MAX_AGENT_PROPOSALS = 3;
    private static final java.util.regex.Pattern EVIDENCE_REF = java.util.regex.Pattern.compile("\\bE(\\d{1,3})\\b");

    /** Lets an agent (Kiro over MCP) or an engineer add an alternative action. Always starts as PROPOSED. */
    @Transactional
    public Recommendation propose(long investigationId, String action, String rationale, Priority priority, String actor) {
        Investigation investigation = get(investigationId);
        if (investigation.status() == Status.CLOSED) {
            throw new InvalidStateException("Investigation " + investigationId + " is closed");
        }
        if (com.basintelligence.agent.BasMcpTools.AGENT.equals(actor)) {
            java.util.Set<String> labels = new java.util.HashSet<>();
            repository.findEvidence(investigationId).forEach(e -> labels.add(e.refLabel()));
            var cited = EVIDENCE_REF.matcher(rationale).results().map(m -> "E" + m.group(1)).toList();
            if (cited.isEmpty() || !labels.containsAll(cited)) {
                throw new IllegalArgumentException("The rationale must cite existing evidence labels of investigation "
                        + investigationId + " (available: " + labels.stream().sorted().toList() + ")");
            }
            // Kiro may retry a call it believes timed out; the same open draft is returned instead of a duplicate.
            var same = repository.findRecommendations(investigationId).stream()
                    .filter(r -> r.proposedBy().equals(actor) && r.status() == RecommendationStatus.PROPOSED
                            && r.action().equalsIgnoreCase(Text.truncate(action.strip(), 1000)))
                    .findFirst();
            if (same.isPresent()) {
                return same.get();
            }
            long open = repository.findRecommendations(investigationId).stream()
                    .filter(r -> r.proposedBy().equals(actor) && r.status() == RecommendationStatus.PROPOSED).count();
            if (open >= MAX_AGENT_PROPOSALS) {
                throw new InvalidStateException("The agent already has " + open + " open proposals on investigation "
                        + investigationId + "; wait for an engineer to review them");
            }
        }
        long id = repository.insertRecommendation(investigationId, Text.truncate(action.strip(), 1000), rationale.strip(),
                priority, actor, clock.instant(), AgentCallContext.currentToolCall());
        if (investigation.status() == Status.REVIEWED) {
            repository.updateStatus(investigationId, Status.OPEN, null);
        }
        audit.record(actor, "RECOMMENDATION_PROPOSED", "recommendation:" + id, Text.truncate(action, 200));
        return repository.findRecommendation(id).orElseThrow();
    }

    /** Human-in-the-loop decision. Four-eyes rule: nobody reviews their own proposal. */
    @Transactional
    public Recommendation review(long recommendationId, boolean approve, String comment, String reviewer) {
        Recommendation rec = repository.findRecommendation(recommendationId)
                .orElseThrow(() -> new NotFoundException("Recommendation not found: " + recommendationId));
        Investigation investigation = get(rec.investigationId()); // also enforces factory access
        if (rec.proposedBy().equals(reviewer)) {
            throw new InvalidStateException("A recommendation cannot be reviewed by the person who proposed it");
        }
        if (investigation.status() == Status.CLOSED) {
            throw new InvalidStateException("Investigation is closed");
        }
        RecommendationStatus decision = approve ? RecommendationStatus.APPROVED : RecommendationStatus.REJECTED;
        if (!repository.review(recommendationId, decision, rec.proposedBy(), reviewer, Text.truncate(comment, 1000), clock.instant())) {
            throw new InvalidStateException("Recommendation " + recommendationId + " has already been reviewed");
        }
        if (approve) {
            maintenance.plan(get(rec.investigationId()).machineId(), recommendationId, rec.action(), rec.priority().name(),
                    reviewer);
        }
        boolean pending = repository.findRecommendations(rec.investigationId()).stream()
                .anyMatch(r -> r.status() == RecommendationStatus.PROPOSED);
        if (!pending) {
            repository.updateStatus(rec.investigationId(), Status.REVIEWED, null);
        }
        audit.record(reviewer, "RECOMMENDATION_" + decision, "recommendation:" + recommendationId, comment);
        return repository.findRecommendation(recommendationId).orElseThrow();
    }

    /**
     * Stores the agent's interpretation as an AI_INTERPRETATION evidence item. Citations to evidence labels that do
     * not exist are returned so the caller can flag them as unverified.
     */
    @Transactional
    public List<String> attachInterpretation(long investigationId, String interpretation, String actor) {
        java.util.Set<String> labels = new java.util.HashSet<>();
        List<Evidence> existing = repository.findEvidence(investigationId);
        existing.forEach(e -> labels.add(e.refLabel()));
        List<String> unknown = EVIDENCE_REF.matcher(interpretation).results().map(m -> "E" + m.group(1))
                .filter(l -> !labels.contains(l)).distinct().toList();
        long cited = EVIDENCE_REF.matcher(interpretation).results().map(m -> m.group(1)).distinct().count();
        meters.counter(com.basintelligence.common.BasMetrics.RAG_CITATIONS, "verified", "true").increment(cited - unknown.size());
        meters.counter(com.basintelligence.common.BasMetrics.RAG_CITATIONS, "verified", "false").increment(unknown.size());
        String label = "E" + (existing.size() + 1);
        repository.insertEvidence(investigationId, new Evidence(label, EvidenceKind.AGENT_NOTE,
                "AI-generated interpretation (" + actor + ")", Text.truncate(interpretation.strip(), 6000),
                unknown.isEmpty() ? actor : actor + "; unverified citations: " + String.join(", ", unknown)));
        audit.record(actor, "INTERPRETATION_ATTACHED", "investigation:" + investigationId, Text.truncate(interpretation, 200));
        return unknown;
    }

    public Investigation get(long id) {
        Investigation investigation = repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Investigation not found: " + id));
        machines.checkAccess(investigation.machineId()); // out-of-scope investigations look missing
        return investigation;
    }

    public InvestigationView view(long id) {
        Investigation investigation = get(id);
        return new InvestigationView(investigation, machines.get(investigation.machineId()), repository.findEvidence(id),
                repository.findRecommendations(id), maintenance.forRecommendations(
                        repository.findRecommendations(id).stream().map(Recommendation::id).toList()),
                repository.findReport(id));
    }

    public List<Investigation> recent(int limit) {
        return repository.findRecent(limit, machines.visible());
    }

    public List<Investigation> forMachine(java.util.UUID machineId, int limit) {
        return repository.findByMachine(machineId, limit);
    }

    public List<InvestigationRepository.RecommendationRow> recommendationRows(boolean pendingOnly, int limit) {
        return repository.findRecommendationRows(pendingOnly, limit, machines.visible());
    }

    public List<InvestigationRepository.ApprovalRow> approvals(int limit) {
        return repository.findApprovals(limit, machines.visible());
    }

    public List<InvestigationRepository.ReportRow> reports(int limit) {
        return repository.findReports(limit, machines.visible());
    }

    public long pendingReviews(java.util.UUID machineId) {
        return repository.countPendingForMachine(machineId);
    }

    public long pendingReviews() {
        return repository.countPending(machines.visible());
    }

    private static Map<Metric, String> telemetryEvidence(Machine machine, TelemetryAnalysis analysis, EvidenceBuilder evidence) {
        Map<Metric, String> refs = new EnumMap<>(Metric.class);
        for (MetricSummary m : analysis.metrics()) {
            boolean notable = m.latestSeverity() != Severity.NORMAL || Math.abs(m.changePct()) >= 0.05
                    || Math.abs(m.recentMean() / m.nominal() - 1) >= 0.08;
            if (!notable) {
                continue;
            }
            OperatingLimits l = machine.type().limits(m.metric());
            String limits = Double.isNaN(l.warnLow())
                    ? "warning ≥ %s, critical ≥ %s".formatted(Text.num(l.warnHigh(), 1), Text.num(l.critHigh(), 1))
                    : "normal band %s–%s".formatted(Text.num(l.warnLow(), 1), Text.num(l.warnHigh(), 1));
            refs.put(m.metric(), evidence.add(EvidenceKind.TELEMETRY, m.metric().label() + " trend",
                    "%s. Latest %s %s (%s). Nominal %s; %s. Window %s to %s, %d samples.".formatted(
                            capitalise(DiagnosisEngine.trend(m)), Text.num(m.latest(), 2), m.unit(), m.latestSeverity(),
                            Text.num(m.nominal(), 1), limits, analysis.from(), analysis.to(), analysis.sampleCount()),
                    "telemetry_reading"));
        }
        if (refs.isEmpty()) {
            evidence.add(EvidenceKind.TELEMETRY, "All channels stable",
                    "All five channels are within their operating envelopes with less than 5%% change over %d samples."
                            .formatted(analysis.sampleCount()),
                    "telemetry_reading");
        }
        return refs;
    }

    private static String summariseAnomalies(List<AnomalyEvent> anomalies) {
        Map<String, Long> counts = anomalies.stream().collect(Collectors.groupingBy(
                e -> e.metric().label() + " / " + e.detector() + " / " + e.severity(), LinkedHashMap::new, Collectors.counting()));
        String breakdown = counts.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("; "));
        return breakdown + ". Most recent: " + anomalies.getFirst().message() + " at " + anomalies.getFirst().detectedAt() + ".";
    }

    private static String riskDetail(RiskAssessment r) {
        String drivers = r.drivers().isEmpty() ? "no feature raises the risk noticeably"
                : r.drivers().stream().limit(3)
                        .map(c -> c.label() + " (+" + Text.num(c.logOdds(), 2) + " log-odds)")
                        .collect(Collectors.joining(", "));
        return "Probability %s from the mean of the last %d readings. Main drivers: %s. Model: %s, hold-out AUC %s, trained on %s."
                .formatted(Text.pct(r.probability()), r.readingsUsed(), drivers, r.model().algorithm(),
                        Text.num(r.model().holdoutAuc(), 3), r.model().trainingData());
    }

    private static String explain(Machine machine, Diagnosis diagnosis, Optional<RiskAssessment> risk,
            Map<Metric, String> metricRefs, String multivariateRef, String anomalyRef, String riskRef,
            List<String> inspectionRefs, List<ManualHit> hits, List<String> manualRefs) {
        Hypothesis primary = diagnosis.primary();
        StringBuilder sb = new StringBuilder();
        sb.append("Summary: ").append(machine.code()).append(" (").append(machine.type().displayName()).append(") ");
        if (primary.code().equals("NO_FAULT")) {
            sb.append("shows no fault signature.");
        } else {
            sb.append("most likely has ").append(primary.title().toLowerCase())
                    .append(" (confidence ").append(Text.pct(primary.confidence())).append(").");
        }
        risk.ifPresent(r -> sb.append(" Estimated failure risk is ").append(r.level()).append(" at ")
                .append(Text.pct(r.probability())).append(" [").append(riskRef).append("]."));
        sb.append(" Recommended priority: ").append(diagnosis.priority()).append(".\n\nSupporting data:\n");
        for (String signal : primary.signals()) {
            sb.append("- ").append(capitalise(signal)).append(refsFor(signal, metricRefs, multivariateRef, inspectionRefs, primary))
                    .append('\n');
        }
        if (anomalyRef != null) {
            sb.append("- Streaming detectors raised anomaly events during the window [").append(anomalyRef).append("].\n");
        }
        if (!hits.isEmpty()) {
            sb.append("\nManual guidance:\n");
            for (int i = 0; i < hits.size(); i++) {
                sb.append("- ").append(hits.get(i).citation()).append(": ").append(firstSentences(hits.get(i).content()))
                        .append(" [").append(manualRefs.get(i)).append("]\n");
            }
        }
        if (diagnosis.hypotheses().size() > 1) {
            sb.append("\nAlternative hypotheses considered:\n");
            diagnosis.hypotheses().stream().skip(1).forEach(h -> sb.append("- ").append(h.title()).append(" (")
                    .append(Text.pct(h.confidence())).append(")\n"));
        }
        sb.append("\nLimitations: the risk model is trained on simulated fault data and the vision model is a fixed-weight "
                + "baseline. Confirm findings on site before acting. Every recommendation needs approval by an engineer.");
        return sb.toString();
    }

    private static String refsFor(String signal, Map<Metric, String> metricRefs, String multivariateRef,
            List<String> inspectionRefs, Hypothesis primary) {
        List<String> refs = new ArrayList<>();
        String lower = signal.toLowerCase();
        for (Map.Entry<Metric, String> e : metricRefs.entrySet()) {
            if (lower.contains(e.getKey().label().toLowerCase())) {
                refs.add(e.getValue());
            }
        }
        if (lower.contains("isolation forest") && multivariateRef != null) {
            refs.add(multivariateRef);
        }
        if (primary.usesInspection() && lower.contains("inspection")) {
            refs.addAll(inspectionRefs);
        }
        return refs.isEmpty() ? "" : " [" + String.join(", ", refs) + "]";
    }

    private static String firstSentences(String content) {
        String flat = content.replaceAll("\\s+", " ").strip();
        String[] sentences = flat.split("(?<=[.!?])\\s+");
        String out = sentences[0] + (sentences.length > 1 ? " " + sentences[1] : "");
        return Text.truncate(out, 320);
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Assigns stable reference labels (E1, E2, ...) in insertion order. */
    private static final class EvidenceBuilder {
        private final List<Evidence> items = new ArrayList<>();

        String add(EvidenceKind kind, String title, String detail, String source) {
            return add(kind, title, detail, source, null);
        }

        String add(EvidenceKind kind, String title, String detail, String source, Long chunkId) {
            String label = "E" + (items.size() + 1);
            items.add(new Evidence(label, kind, Text.truncate(title, 255), detail, Text.truncate(source, 255), chunkId, null));
            return label;
        }

        List<Evidence> items() {
            return items;
        }
    }
}

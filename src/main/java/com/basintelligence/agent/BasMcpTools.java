package com.basintelligence.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.basintelligence.analytics.AnomalyEvent;
import com.basintelligence.analytics.Stats;
import com.basintelligence.analytics.TelemetryAnalysis;
import com.basintelligence.common.Text;
import com.basintelligence.config.BasProperties;
import com.basintelligence.factory.FactoryService;
import com.basintelligence.investigation.DiagnosisEngine;
import com.basintelligence.investigation.EvidenceClass;
import com.basintelligence.investigation.InvestigationModels.Evidence;
import com.basintelligence.investigation.InvestigationModels.InvestigationView;
import com.basintelligence.investigation.InvestigationModels.Priority;
import com.basintelligence.investigation.InvestigationModels.Recommendation;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.knowledge.ManualService;
import com.basintelligence.machine.DeviceTypeCatalog;
import com.basintelligence.machine.HealthStatus;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.Metric;
import com.basintelligence.maintenance.MaintenanceService;
import com.basintelligence.risk.PredictionService;
import com.basintelligence.risk.RiskAssessment;
import com.basintelligence.risk.RiskService;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.TelemetryRollupService;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.vision.Inspection;
import com.basintelligence.vision.InspectionService;

/**
 * The single maintenance-investigation agent's toolbox, published over MCP. Kiro runs the tool-calling loop: it
 * asks for a tool, Java validates the arguments, executes the call against application services and returns the
 * result. Kiro never touches the database, files, the shell or the machines directly.
 * <p>
 * Every result says which {@link EvidenceClass} each part belongs to, so the agent's answer can keep measured
 * evidence, rule-based detections, model predictions, documentation and its own interpretation apart.
 * <p>
 * Seven tools only read. {@code generateMaintenanceReport} records an investigation and
 * {@code createMaintenanceDraft} records a PROPOSED recommendation. Nothing the agent does changes machine settings,
 * and only an engineer (never the agent, never the proposer) can approve.
 */
@Component
public class BasMcpTools {

    /** Identity recorded in the audit log for all agent actions. */
    public static final String AGENT = "kiro-agent";
    private static final Pattern MACHINE_CODE = Pattern.compile("[A-Z0-9][A-Z0-9-]{1,39}");

    // ---------- result types ----------

    /** A section of a tool result: what it is, how much to trust it and where it came from. */
    public record Labeled<T>(EvidenceClass evidenceClass, String meaning, String source, T data) {
        static <T> Labeled<T> of(EvidenceClass c, String source, T data) {
            return new Labeled<>(c, c.meaning(), source, data);
        }
    }

    public record MachineStatus(String code, String name, String type, String typeName, String factory, String location,
            HealthStatus health, Instant lastReadingAt, String dataFreshness, long openInvestigations,
            long recommendationsAwaitingApproval) {
    }

    public record MetricSummary(String metric, String unit, double latest, double min, double mean, double max,
            double nominal, String latestVsLimits) {
    }

    public record SensorReadings(String machineCode, int windowMinutes, String resolution, int samples,
            Labeled<List<MetricSummary>> summary, Labeled<List<TelemetryReading>> readings) {
    }

    public record AnomalyAnalysis(String machineCode, int windowMinutes, Labeled<List<AnomalyEvent>> detectedEvents,
            Labeled<List<TelemetryAnalysis.MetricSummary>> trends, Labeled<List<DiagnosisEngine.Hypothesis>> faultSignatures,
            Labeled<TelemetryAnalysis.MultivariateResult> multivariateModel, Labeled<List<Inspection>> visualInspections,
            String note) {
    }

    public record RiskPrediction(String machineCode, Labeled<RiskAssessment> prediction,
            Labeled<List<PredictionService.Prediction>> recentHistory, List<String> limitations) {
    }

    public record IncidentSummary(long investigationId, Instant openedAt, String diagnosisAtTheTime, String riskLevel,
            String status, Instant closedAt, String reportTitle, String requestedBy) {
    }

    public record HistoricalFailures(String machineCode, int days,
            Labeled<List<com.basintelligence.failure.FailureEventService.FailureEvent>> confirmedFailures,
            Labeled<List<IncidentSummary>> incidents,
            Labeled<List<MaintenanceService.MaintenanceRecord>> maintenance, Labeled<Integer> criticalAnomalyEvents,
            Labeled<Integer> sensorFaults, String note) {
    }

    public record ManualPassage(String citation, String document, String section, Integer pageStart, Integer pageEnd,
            String text, double score) {
    }

    public record ManualSearch(String query, String retriever, Labeled<List<ManualPassage>> passages) {
    }

    public record Specifications(String code, String name, String type, String typeName, String factory,
            String location, Instant registeredAt, Labeled<List<DeviceTypeCatalog.LimitRow>> operatingEnvelope,
            String vibrationStandard) {
    }

    public record ReportResult(long investigationId, String machineCode, String reportMarkdown,
            List<String> unverifiedCitations, List<Evidence> evidence, String reviewUrl, String nextStep) {
    }

    public record DraftResult(long recommendationId, long investigationId, String status, String priority,
            String reviewUrl, String approvalRule) {
    }

    // ---------- dependencies ----------

    private final MachineService machines;
    private final FactoryService factories;
    private final DeviceTypeCatalog deviceTypes;
    private final TelemetryService telemetry;
    private final TelemetryRollupService rollups;
    private final RiskService risk;
    private final PredictionService predictions;
    private final InspectionService inspections;
    private final ManualService manuals;
    private final InvestigationService investigations;
    private final MaintenanceService maintenance;
    private final BasProperties properties;
    private final Clock clock;
    private final DiagnosisEngine engine = new DiagnosisEngine();
    @org.springframework.beans.factory.annotation.Autowired
    private com.basintelligence.failure.FailureEventService failures;
    @org.springframework.beans.factory.annotation.Autowired
    private com.basintelligence.telemetry.SensorFaultService sensorFaults;

    public BasMcpTools(MachineService machines, FactoryService factories, DeviceTypeCatalog deviceTypes,
            TelemetryService telemetry, TelemetryRollupService rollups, RiskService risk, PredictionService predictions,
            InspectionService inspections, ManualService manuals, InvestigationService investigations,
            MaintenanceService maintenance, BasProperties properties, Clock clock) {
        this.machines = machines;
        this.factories = factories;
        this.deviceTypes = deviceTypes;
        this.telemetry = telemetry;
        this.rollups = rollups;
        this.risk = risk;
        this.predictions = predictions;
        this.inspections = inspections;
        this.manuals = manuals;
        this.investigations = investigations;
        this.maintenance = maintenance;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------- 1. getMachineStatus ----------

    @Tool(resultConverter = JsonToolResultConverter.class, name = "getMachineStatus", description = """
            Read the current state of one machine (or all machines when machineCode is omitted): health from the \
            latest reading, how fresh the data is, open investigations and recommendations awaiting approval. \
            Start every investigation here.""")
    public List<MachineStatus> getMachineStatus(
            @ToolParam(description = "Machine code, e.g. CNC-01. Omit to list every machine.", required = false)
            String machineCode) {
        List<Machine> list = machineCode == null || machineCode.isBlank() ? machines.list() : List.of(machine(machineCode));
        return list.stream().map(this::status).toList();
    }

    // ---------- 2. getSensorReadings ----------

    @Tool(resultConverter = JsonToolResultConverter.class, name = "getSensorReadings", description = """
            Recent sensor readings (temperature °C, vibration mm/s RMS, speed rpm, torque N·m, pressure bar) with \
            per-channel min/mean/max/latest and position against the machine's limits. Up to 3 hours returns raw \
            readings (evenly downsampled to maxPoints); longer windows return 5-minute averages. MEASURED evidence.""")
    public SensorReadings getSensorReadings(
            @ToolParam(description = "Machine code") String machineCode,
            @ToolParam(description = "Window in minutes, 10-10080. Default 60.", required = false) Integer windowMinutes,
            @ToolParam(description = "Maximum readings to return, 5-120. Default 30.", required = false) Integer maxPoints) {
        Machine m = machine(machineCode);
        int window = clamp(windowMinutes, 60, 10, 10_080);
        int points = clamp(maxPoints, 30, 5, 120);
        List<TelemetryReading> readings;
        String resolution;
        if (window <= 180) {
            readings = telemetry.recent(m.id(), Duration.ofMinutes(window));
            resolution = "raw readings";
        } else {
            readings = rollups.since(m.id(), clock.instant().minus(Duration.ofMinutes(window))).stream()
                    .map(r -> new TelemetryReading(m.id(), r.bucketStart(), r.temperatureAvg(), r.vibrationAvg(), r.rpmAvg(),
                            r.torqueAvg(), r.pressureAvg(), com.basintelligence.telemetry.TelemetrySource.EDGE))
                    .toList();
            resolution = "5-minute averages";
        }
        List<MetricSummary> summary = new ArrayList<>();
        for (Metric metric : Metric.values()) {
            double[] v = readings.stream().mapToDouble(metric::of).toArray();
            if (v.length == 0) {
                continue;
            }
            Stats s = Stats.of(v);
            var limits = m.type().limits(metric);
            summary.add(new MetricSummary(metric.name(), metric.unit(), v[v.length - 1], s.min(), s.mean(), s.max(),
                    limits.nominal(), limits.evaluate(v[v.length - 1]).name()));
        }
        return new SensorReadings(m.code(), window, resolution, readings.size(),
                Labeled.of(EvidenceClass.MEASURED, "telemetry table, " + resolution, summary),
                Labeled.of(EvidenceClass.MEASURED, "telemetry table", downsample(readings, points)));
    }

    // ---------- 3. detectAnomaly ----------

    @Tool(resultConverter = JsonToolResultConverter.class, name = "detectAnomaly", description = """
            Run anomaly analysis over a window: stored detector events (limit breaches and z-score spikes), \
            per-channel trends, deterministic fault-signature rules, an Isolation Forest score of the combined \
            channels, and recent visual inspections. Each section is labelled RULE_BASED, MEASURED or MODEL_PREDICTION.""")
    public AnomalyAnalysis detectAnomaly(
            @ToolParam(description = "Machine code") String machineCode,
            @ToolParam(description = "Window in minutes, 30-1440. Default 120.", required = false) Integer windowMinutes) {
        Machine m = machine(machineCode);
        int window = clamp(windowMinutes, 120, 30, 1440);
        TelemetryAnalysis analysis = telemetry.analyze(m, Duration.ofMinutes(window));
        List<AnomalyEvent> events = telemetry.anomalies(m.id(), Duration.ofMinutes(window), 50);
        List<Inspection> recentInspections = inspections.recent(m.id(), Duration.ofDays(7), 5);
        var hypotheses = analysis.hasData()
                ? engine.diagnose(new DiagnosisEngine.Input(analysis, events, Optional.empty(), recentInspections)).hypotheses()
                : List.<DiagnosisEngine.Hypothesis>of();
        return new AnomalyAnalysis(m.code(), window,
                Labeled.of(EvidenceClass.RULE_BASED, "streaming detectors at ingestion: operating envelope + z-score "
                        + properties.telemetry().zscoreThreshold() + "σ", events),
                Labeled.of(EvidenceClass.MEASURED, "statistics of " + analysis.sampleCount() + " readings", analysis.metrics()),
                Labeled.of(EvidenceClass.RULE_BASED, "DiagnosisEngine fault-signature rules (confidence is a rule score, "
                        + "not a probability)", hypotheses),
                Labeled.of(EvidenceClass.MODEL_PREDICTION, "Smile Isolation Forest fitted on the earlier 60% of the window",
                        analysis.multivariate()),
                Labeled.of(EvidenceClass.MODEL_PREDICTION, "OpenCV + ONNX defect model, last 7 days", recentInspections),
                analysis.hasData() ? null : "No readings in this window: the machine may be offline. Check the machine status.");
    }

    // ---------- 4. predictFailureRisk ----------

    @Tool(resultConverter = JsonToolResultConverter.class, name = "predictFailureRisk", description = """
            Failure-risk probability from the ML model, with each feature's contribution to the log-odds, model \
            quality (hold-out AUC) and the recent stored predictions to show the trend. MODEL_PREDICTION: report it \
            as a model estimate, never as a measured fact. The prediction is recorded in model_predictions.""")
    public RiskPrediction predictFailureRisk(@ToolParam(description = "Machine code") String machineCode) {
        Machine m = machine(machineCode);
        Optional<RiskAssessment> assessment = risk.assess(m);
        assessment.ifPresent(a -> predictions.record(m, a, PredictionService.Trigger.AGENT, null));
        return new RiskPrediction(m.code(),
                Labeled.of(EvidenceClass.MODEL_PREDICTION, risk.modelInfo().algorithm() + ", hold-out AUC "
                        + Text.num(risk.modelInfo().holdoutAuc(), 3), assessment.orElse(null)),
                Labeled.of(EvidenceClass.MODEL_PREDICTION, "model_predictions table", predictions.recent(m.id(), 10)),
                List.of("Trained on " + risk.modelInfo().trainingData() + ", not on this plant's failure history.",
                        "Uses the mean of the last readings; a sudden fault may appear in readings before it moves the risk.",
                        "Contributions are exact for this linear model: log-odds = intercept + sum of contributions."));
    }

    // ---------- 5. getHistoricalFailures ----------

    @Tool(resultConverter = JsonToolResultConverter.class, name = "getHistoricalFailures", description = """
            Previous incidents for a machine: past investigations with the diagnosis recorded at the time, incident \
            reports, maintenance records (planned and completed, with technician notes) and the count of critical \
            anomaly events. DOCUMENTED evidence.""")
    public HistoricalFailures getHistoricalFailures(
            @ToolParam(description = "Machine code") String machineCode,
            @ToolParam(description = "Look-back in days, 1-730. Default 365.", required = false) Integer days) {
        Machine m = machine(machineCode);
        int d = clamp(days, 365, 1, 730);
        Instant since = clock.instant().minus(Duration.ofDays(d));
        List<IncidentSummary> incidents = investigations.forMachine(m.id(), 50).stream()
                .filter(i -> !i.createdAt().isBefore(since))
                .map(i -> {
                    var report = investigations.view(i.id()).report();
                    return new IncidentSummary(i.id(), i.createdAt(), i.diagnosis(),
                            i.riskLevel() == null ? null : i.riskLevel().name(), i.status().name(), i.closedAt(),
                            report.map(r -> r.title()).orElse(null), i.requestedBy());
                }).toList();
        List<MaintenanceService.MaintenanceRecord> work = maintenance.forMachine(m.id(), 50).stream()
                .filter(r -> !r.plannedAt().isBefore(since)).toList();
        int critical = (int) telemetry.anomalies(m.id(), Duration.ofDays(d), 1000).stream()
                .filter(e -> e.severity() == com.basintelligence.machine.Severity.CRITICAL).count();
        var confirmed = failures.forMachine(m.id(), 50).stream().filter(f -> !f.occurredAt().isBefore(since)).toList();
        return new HistoricalFailures(m.code(), d,
                Labeled.of(EvidenceClass.DOCUMENTED, "failure_events (confirmed by a person who inspected the machine)", confirmed),
                Labeled.of(EvidenceClass.DOCUMENTED, "investigations and incident_reports", incidents),
                Labeled.of(EvidenceClass.DOCUMENTED, "maintenance_records", work),
                Labeled.of(EvidenceClass.RULE_BASED, "anomaly_events (kept " + properties.telemetry().retention().toDays()
                        + " days)", critical),
                Labeled.of(EvidenceClass.MEASURED, "sensor_faults: readings rejected as untrustworthy (not machine behaviour)",
                        sensorFaults.recent(m.id(), Duration.ofDays(Math.min(d, 7)), 1000).size()),
                "Only confirmedFailures are actual failures. Diagnoses, anomalies and risk predictions are not; sensor faults "
                        + "are data problems, not machine behaviour.");
    }

    // ---------- 6. searchMaintenanceManual ----------

    @Tool(resultConverter = JsonToolResultConverter.class, name = "searchMaintenanceManual", description = """
            Search the maintenance manual library (PostgreSQL full-text search; BM25 when offline). Returns passages \
            with a citation "Document › Section, p. N". Quote the citation whenever you use a passage. DOCUMENTED.""")
    public ManualSearch searchMaintenanceManual(
            @ToolParam(description = "Symptoms and machine type, e.g. 'spindle bearing vibration rising temperature'")
            String query,
            @ToolParam(description = "Machine code to restrict to manuals for its type plus general manuals",
                    required = false) String machineCode,
            @ToolParam(description = "Passages to return, 1-8. Default 4.", required = false) Integer topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        var type = machineCode == null || machineCode.isBlank() ? null : machine(machineCode).type();
        String q = Text.truncate(query.strip(), 300);
        var passages = manuals.search(q, type, clamp(topK, 4, 1, 8)).stream()
                .map(h -> new ManualPassage(h.citation(), h.documentTitle(), h.section(), h.pageStart(), h.pageEnd(),
                        h.content(), h.score()))
                .toList();
        return new ManualSearch(q, manuals.retrieverName(), Labeled.of(EvidenceClass.DOCUMENTED, "documents, document_chunks",
                passages));
    }

    // ---------- 7. getMachineSpecifications ----------

    @Tool(resultConverter = JsonToolResultConverter.class, name = "getMachineSpecifications", description = """
            Machine configuration: type, factory, location, registration date and the operating envelope (nominal, \
            warning and critical limits per channel) used by the detectors. Use it to judge readings against limits.""")
    public Specifications getMachineSpecifications(@ToolParam(description = "Machine code") String machineCode) {
        Machine m = machine(machineCode);
        return new Specifications(m.code(), m.name(), m.type().name(), m.type().displayName(), factoryName(m), m.location(),
                m.createdAt(), Labeled.of(EvidenceClass.DOCUMENTED, "device_type_limits", deviceTypes.limits(m.type())),
                "Vibration zones follow the ISO 10816-3 style used in the plant guide: A < 2.8, B < 4.5, C < 7.1, D ≥ 7.1 mm/s RMS");
    }

    // ---------- 8. generateMaintenanceReport ----------

    @Tool(resultConverter = JsonToolResultConverter.class, name = "generateMaintenanceReport", description = """
            Produce the maintenance report after gathering evidence. Java re-collects the evidence itself (it does not \
            trust numbers passed in), labels it E1, E2..., runs the rule-based diagnosis and stores your interpretation \
            as AI_INTERPRETATION. Cite evidence labels like [E2] in the interpretation. Does not draft a \
            recommendation; offer the user a maintenance draft afterwards.""")
    public ReportResult generateMaintenanceReport(
            @ToolParam(description = "Machine code") String machineCode,
            @ToolParam(description = "The user's question, e.g. 'Why is CNC-01 showing a high failure risk?'") String question,
            @ToolParam(description = "Your interpretation, max 4000 characters. Keep measured facts, model predictions "
                    + "and your inference visibly separate.") String interpretation) {
        Machine m = machine(machineCode);
        requireText(question, "question", 1000);
        requireText(interpretation, "interpretation", 4000);
        InvestigationView created = investigations.investigate(m.id(), question.strip(), AGENT, false);
        long id = created.investigation().id();
        List<String> unverified = investigations.attachInterpretation(id, interpretation, AGENT);
        InvestigationView view = investigations.view(id);
        return new ReportResult(id, m.code(), render(view, unverified), unverified, view.evidence(),
                "/investigations/" + id,
                "Offer the user a maintenance draft. Create it only if they agree.");
    }

    // ---------- 9. createMaintenanceDraft ----------

    @Tool(resultConverter = JsonToolResultConverter.class, name = "createMaintenanceDraft", description = """
            Prepare an approval request: records a PROPOSED maintenance action on an investigation for an engineer to \
            approve or reject in the web UI. The rationale must cite evidence labels of that investigation (e.g. E2, E4). \
            This does not schedule or perform any work, and the agent can never approve it.""")
    public DraftResult createMaintenanceDraft(
            @ToolParam(description = "Investigation id returned when the maintenance report was generated") long investigationId,
            @ToolParam(description = "Concrete action, max 1000 characters") String action,
            @ToolParam(description = "Why, citing evidence labels and manual citations, max 4000 characters") String rationale,
            @ToolParam(description = "LOW, MEDIUM, HIGH or URGENT") String priority) {
        requireText(action, "action", 1000);
        requireText(rationale, "rationale", 4000);
        Priority p;
        try {
            p = Priority.valueOf(priority == null ? "" : priority.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("priority must be one of " + Arrays.toString(Priority.values()));
        }
        Recommendation r = investigations.propose(investigationId, action.strip(), rationale.strip(), p, AGENT);
        return new DraftResult(r.id(), investigationId, r.status().name(), r.priority().name(),
                "/investigations/" + investigationId,
                "An engineer other than the proposer must approve or reject it. The agent has no approval tool.");
    }

    // ---------- helpers ----------

    private MachineStatus status(Machine m) {
        long open = investigations.forMachine(m.id(), 100).stream().filter(i -> i.status().name().equals("OPEN")).count();
        String freshness;
        if (m.lastSeenAt() == null) {
            freshness = "no data received yet";
        } else {
            long minutes = Duration.between(m.lastSeenAt(), clock.instant()).toMinutes();
            freshness = minutes <= 5 ? "live (last reading " + minutes + " min ago)"
                    : "STALE: last reading " + minutes + " min ago; health may be out of date";
        }
        return new MachineStatus(m.code(), m.name(), m.type().name(), m.type().displayName(), factoryName(m), m.location(),
                m.healthStatus(), m.lastSeenAt(), freshness, open, investigations.pendingReviews(m.id()));
    }

    private String factoryName(Machine m) {
        return factories.list().stream().filter(f -> f.id().equals(m.factoryId())).map(f -> f.name()).findFirst().orElse(null);
    }

    /** Report sections in fixed order of trust: measured, rule-based, model, documented, then the AI's reading. */
    static String render(InvestigationView view, List<String> unverified) {
        StringBuilder md = new StringBuilder();
        var inv = view.investigation();
        md.append("# Maintenance report INV-").append(inv.id()).append(": ").append(view.machine().code()).append("\n\n");
        md.append("**Question:** ").append(inv.question()).append("\n\n");
        for (EvidenceClass c : EvidenceClass.values()) {
            List<Evidence> items = view.evidence().stream().filter(e -> e.kind().evidenceClass() == c).toList();
            if (c == EvidenceClass.RULE_BASED) {
                md.append("## ").append(c.label()).append("\n_").append(c.meaning()).append("._\n\n");
                md.append("- Automated diagnosis: ").append(inv.diagnosis()).append('\n');
                items.forEach(e -> md.append("- [").append(e.refLabel()).append("] ").append(e.title()).append(": ")
                        .append(Text.truncate(e.detail(), 400)).append('\n'));
                md.append('\n');
                continue;
            }
            if (items.isEmpty()) {
                continue;
            }
            md.append("## ").append(c.label()).append("\n_").append(c.meaning()).append("._\n\n");
            for (Evidence e : items) {
                md.append("- [").append(e.refLabel()).append("] **").append(e.title()).append("**: ")
                        .append(c == EvidenceClass.AI_INTERPRETATION ? "\n\n" + e.detail() : Text.truncate(e.detail(), 600))
                        .append(e.source() == null || c == EvidenceClass.AI_INTERPRETATION ? "" : " _(source: " + e.source() + ")_")
                        .append('\n');
            }
            md.append('\n');
        }
        if (!unverified.isEmpty()) {
            md.append("> Warning: the interpretation cites ").append(String.join(", ", unverified))
                    .append(", which do not exist in this report's evidence.\n\n");
        }
        md.append("_Status: ").append(inv.status()).append(". No maintenance action has been approved. An engineer reviews "
                + "proposals at /investigations/").append(inv.id()).append("._\n");
        return md.toString();
    }

    private Machine machine(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("machineCode is required");
        }
        String normalised = code.strip().toUpperCase(Locale.ROOT);
        if (!MACHINE_CODE.matcher(normalised).matches()) {
            throw new IllegalArgumentException("machineCode must be 2-40 letters, digits or '-'");
        }
        return machines.getByCode(normalised);
    }

    private static void requireText(String value, String name, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(name + " must be at most " + max + " characters");
        }
    }

    private static <T> List<T> downsample(List<T> list, int max) {
        if (list.size() <= max) {
            return list;
        }
        List<T> out = new ArrayList<>(max);
        double step = (list.size() - 1) / (double) (max - 1);
        for (int i = 0; i < max; i++) {
            out.add(list.get((int) Math.round(i * step)));
        }
        return out;
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        return value == null ? fallback : Math.clamp(value, min, max);
    }
}

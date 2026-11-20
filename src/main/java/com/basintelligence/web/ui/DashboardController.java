package com.basintelligence.web.ui;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.basintelligence.agent.AgentAuditService;
import com.basintelligence.agent.McpToolExposure;
import com.basintelligence.analytics.AnomalyEvent;
import com.basintelligence.analytics.DataQuality;
import com.basintelligence.common.AuditService;
import com.basintelligence.common.Text;
import com.basintelligence.factory.FactoryService;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.knowledge.ManualService;
import com.basintelligence.machine.HealthStatus;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.Severity;
import com.basintelligence.maintenance.MaintenanceService;
import com.basintelligence.ml.MlPredictionService;
import com.basintelligence.risk.RiskAssessment;
import com.basintelligence.risk.RiskLevel;
import com.basintelligence.risk.RiskService;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.web.RateLimited;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Screen 1, factory overview, plus the admin pages (models, agent activity, audit). */
@Controller
public class DashboardController {

    /** Alerts listed on the overview; the count covers every alert of the last hour. */
    static final int ALERTS_SHOWN = 10;

    public record MachineCard(Machine machine, Optional<RiskAssessment> risk, int anomalies24h, DataQuality quality,
            boolean needsAttention) {

        /** Failure-risk level, or NONE before the model has assessed the machine. */
        public String level() {
            return risk.map(r -> r.level().name()).orElse("NONE");
        }

        public double probability() {
            return risk.map(RiskAssessment::probability).orElse(0.0);
        }

        public String health() {
            return machine.healthStatus() == null ? HealthStatus.UNKNOWN.name() : machine.healthStatus().name();
        }

        public String connectivity() {
            return quality.connectivity().name();
        }

        /** Warning or critical health, or risk HIGH or above: in costume the card flickers and a ghost peeks over it. */
        public boolean restless() {
            HealthStatus h = machine.healthStatus();
            return h == HealthStatus.WARNING || h == HealthStatus.CRITICAL
                    || risk.map(r -> r.level().ordinal() >= RiskLevel.HIGH.ordinal()).orElse(false);
        }

        /** Critical health or critical risk: in costume the card trembles. */
        public boolean possessed() {
            return machine.healthStatus() == HealthStatus.CRITICAL
                    || risk.map(r -> r.level() == RiskLevel.CRITICAL).orElse(false);
        }

        /** No live readings: in costume the card fades under cobwebs. */
        public boolean quiet() {
            return quality.connectivity() != DataQuality.Connectivity.ONLINE;
        }

        /** What the card says when the data has gone quiet; null while readings arrive on time. */
        public String quietText() {
            return switch (quality.connectivity()) {
                case ONLINE -> null;
                case STALE -> "Going quiet: readings are late.";
                case OFFLINE -> "Gone silent: no live readings. Cobwebs are growing.";
                case NO_DATA -> "Gone silent: no readings in the last hour. Cobwebs are growing.";
            };
        }

        public String anomaliesText() {
            return anomalies24h + (anomalies24h == 1 ? " anomaly" : " anomalies") + " / 24 h";
        }
    }

    public record Alert(String machineCode, AnomalyEvent event) {

        /** Identifies the alert between two refreshes of the overview (anomaly events have no id of their own). */
        public String key() {
            return machineCode + "|" + event.detectedAt().toEpochMilli() + "|" + event.metric() + "|" + event.detector();
        }
    }

    public record RiskSummary(Map<RiskLevel, Long> byLevel, double meanProbability, MachineCard highest, int assessed) {

        public String assessedText() {
            return assessed + (assessed == 1 ? " machine" : " machines") + " assessed by the failure-risk model";
        }

        /** e.g. "highest CNC-01 71%"; null when no machine has been assessed. */
        public String highestText() {
            return highest == null ? null : "highest " + highest.machine().code() + " " + Text.pct(highest.probability());
        }
    }

    /**
     * The narrator line under the plant name in the haunted costume. The mood is alarm when a critical alert came in
     * during the last hour, uneasy when a machine needs attention, and calm otherwise. The page and /overview.json
     * use the same text.
     *
     * @param restless machines whose card flickers (warning or critical health, or risk HIGH or above)
     * @param quiet    other machines without live readings, whose card fades under cobwebs
     */
    public record NightWatch(String mood, String text) {

        static NightWatch of(long criticalAlerts, long attention, long restless, long quiet) {
            if (criticalAlerts > 0) {
                return new NightWatch("alarm", "Something stirs: " + criticalAlerts + " critical alert"
                        + (criticalAlerts == 1 ? "" : "s") + " in the last hour.");
            }
            if (restless > 0) {
                String text = restless == 1 ? "1 machine is restless tonight: its card glows and flickers below."
                        : restless + " machines are restless tonight: their cards glow and flicker below.";
                return new NightWatch("uneasy", quiet == 0 ? text
                        : text + " " + quiet + (quiet == 1 ? " more has" : " more have") + " gone quiet.");
            }
            if (quiet > 0) {
                return new NightWatch("uneasy", quiet == 1
                        ? "1 machine has gone quiet tonight: cobwebs are growing on its card."
                        : quiet + " machines have gone quiet tonight: cobwebs are growing on their cards.");
            }
            if (attention > 0) {
                return new NightWatch("uneasy", attention == 1 ? "1 machine needs attention tonight."
                        : attention + " machines need attention tonight.");
            }
            return new NightWatch("calm", "All quiet. Every machine is resting easy.");
        }
    }

    /** Everything on the overview that changes while the page is open, computed once for the page or one refresh. */
    record Overview(List<MachineCard> cards, List<Alert> alerts, RiskSummary riskSummary, long pendingReviews,
            Instant at) {

        long attention() {
            return cards.stream().filter(MachineCard::needsAttention).count();
        }

        long normal() {
            return cards.size() - attention();
        }

        long criticalAlerts() {
            return alerts.stream().filter(a -> a.event().severity() == Severity.CRITICAL).count();
        }

        NightWatch nightWatch() {
            return NightWatch.of(criticalAlerts(), attention(), cards.stream().filter(MachineCard::restless).count(),
                    cards.stream().filter(c -> c.quiet() && !c.restless()).count());
        }
    }

    private final MachineService machines;
    private final FactoryService factories;
    private final RiskService risk;
    private final TelemetryService telemetry;
    private final InvestigationService investigations;
    private final MaintenanceService maintenance;
    private final ManualService manuals;
    private final AuditService audit;
    private final AgentAuditService agentAudit;
    private final MlPredictionService ml;
    private final com.basintelligence.users.AccessScopeService access;
    private final Formatters fmt;
    private final ObjectMapper json;
    private final Clock clock;

    public DashboardController(MachineService machines, FactoryService factories, RiskService risk,
            TelemetryService telemetry, InvestigationService investigations, MaintenanceService maintenance,
            ManualService manuals, AuditService audit, AgentAuditService agentAudit, MlPredictionService ml,
            com.basintelligence.users.AccessScopeService access, Formatters fmt, ObjectMapper json, Clock clock) {
        this.access = access;
        this.machines = machines;
        this.factories = factories;
        this.risk = risk;
        this.telemetry = telemetry;
        this.investigations = investigations;
        this.maintenance = maintenance;
        this.manuals = manuals;
        this.audit = audit;
        this.agentAudit = agentAudit;
        this.ml = ml;
        this.fmt = fmt;
        this.json = json;
        this.clock = clock;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String dashboard(Model model) throws JsonProcessingException {
        Overview overview = overview();
        List<MachineCard> cards = overview.cards();
        model.addAttribute("factory", currentFactory());
        model.addAttribute("cards", cards);
        model.addAttribute("total", cards.size());
        model.addAttribute("normal", overview.normal());
        model.addAttribute("attention", overview.attention());
        model.addAttribute("alerts", overview.alerts().stream().limit(ALERTS_SHOWN).toList());
        model.addAttribute("alertCount", overview.alerts().size());
        model.addAttribute("criticalAlerts", overview.criticalAlerts());
        model.addAttribute("nightWatch", overview.nightWatch());
        model.addAttribute("riskSummary", overview.riskSummary());
        // The figures the page is drawn with, so the live view knows what changed at its first refresh.
        model.addAttribute("live", json.writeValueAsString(LiveOverview.of(overview, fmt)));
        model.addAttribute("maintenanceEvents", maintenance.recent(8));
        model.addAttribute("machineCodes", cards.stream().map(MachineCard::machine)
                .collect(Collectors.toMap(Machine::id, Machine::code)));
        model.addAttribute("investigations", investigations.recent(6));
        model.addAttribute("pendingReviews", overview.pendingReviews());
        model.addAttribute("manualChunks", manuals.indexedChunks());
        return "dashboard";
    }

    /**
     * Every figure of the overview that can change while it is open: counts, risk summary, recent alerts and each
     * machine card, with the texts the page shows. The overview refreshes from it every 30 s. Scoped by
     * {@link MachineService#list()} like the page, and rate limited like other risk assessments.
     */
    @GetMapping(path = "/overview.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(RateLimited.Kind.PREDICTION)
    @ResponseBody
    public LiveOverview overviewJson() {
        return LiveOverview.of(overview(), fmt);
    }

    private Overview overview() {
        Instant now = clock.instant();
        List<MachineCard> cards = new ArrayList<>();
        List<Alert> alerts = new ArrayList<>();
        for (Machine m : machines.list()) {
            Optional<RiskAssessment> r = risk.assess(m);
            List<AnomalyEvent> events = telemetry.anomalies(m.id(), Duration.ofHours(24), 500);
            DataQuality q = DataQuality.of(telemetry.recent(m.id(), Duration.ofMinutes(60)), now);
            boolean attention = m.healthStatus() != HealthStatus.NORMAL
                    || r.map(x -> x.level().ordinal() >= RiskLevel.HIGH.ordinal()).orElse(false)
                    || q.connectivity() != DataQuality.Connectivity.ONLINE;
            cards.add(new MachineCard(m, r, events.size(), q, attention));
            events.stream().filter(e -> e.detectedAt().isAfter(now.minus(Duration.ofHours(1))))
                    .forEach(e -> alerts.add(new Alert(m.code(), e)));
        }
        alerts.sort(Comparator.comparing((Alert a) -> a.event().detectedAt()).reversed());

        Map<RiskLevel, Long> byLevel = new EnumMap<>(RiskLevel.class);
        for (RiskLevel l : RiskLevel.values()) {
            byLevel.put(l, cards.stream().filter(c -> c.risk().map(x -> x.level() == l).orElse(false)).count());
        }
        List<MachineCard> assessed = cards.stream().filter(c -> c.risk().isPresent()).toList();
        RiskSummary summary = new RiskSummary(byLevel,
                assessed.stream().mapToDouble(MachineCard::probability).average().orElse(0),
                assessed.stream().max(Comparator.comparingDouble(MachineCard::probability)).orElse(null),
                assessed.size());
        return new Overview(List.copyOf(cards), List.copyOf(alerts), summary, investigations.pendingReviews(), now);
    }

    @GetMapping("/models")
    public String models(Model model) {
        model.addAttribute("cards", ml.models());
        return "models";
    }

    @GetMapping("/agent")
    @PreAuthorize("hasRole('ADMIN')")
    public String agent(Model model) {
        model.addAttribute("sessions", agentAudit.recentSessions(50));
        model.addAttribute("calls", agentAudit.recentCalls(200));
        model.addAttribute("mcpNames", mcpNames());
        return "agent";
    }

    @GetMapping("/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public String audit(Model model) {
        model.addAttribute("entries", audit.recent(200));
        return "audit";
    }

    /** The signed-in person's factory; administrators see all factories. */
    private com.basintelligence.factory.Factory currentFactory() {
        var scope = access.current();
        if (scope.factoryId() != null) {
            return factories.get(scope.factoryId());
        }
        List<com.basintelligence.factory.Factory> all = factories.list();
        return all.size() == 1 ? all.getFirst()
                : new com.basintelligence.factory.Factory(null, "ALL", "All factories", all.size() + " sites", null);
    }

    static Map<String, String> mcpNames() {
        Map<String, String> names = new java.util.HashMap<>();
        McpToolExposure.NAMES.forEach((java, exposed) -> names.put(java, exposed.mcpName()));
        return names;
    }
}

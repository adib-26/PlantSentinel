package com.basintelligence.web.ui;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import com.basintelligence.agent.AgentAuditService;
import com.basintelligence.agent.McpToolExposure;
import com.basintelligence.analytics.AnomalyEvent;
import com.basintelligence.analytics.DataQuality;
import com.basintelligence.common.AuditService;
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

/** Screen 1, factory overview, plus the admin pages (models, agent activity, audit). */
@Controller
public class DashboardController {

    public record MachineCard(Machine machine, Optional<RiskAssessment> risk, int anomalies24h, DataQuality quality,
            boolean needsAttention) {
    }

    public record Alert(String machineCode, AnomalyEvent event) {
    }

    public record RiskSummary(Map<RiskLevel, Long> byLevel, double meanProbability, MachineCard highest, int assessed) {
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
    private final Clock clock;

    public DashboardController(MachineService machines, FactoryService factories, RiskService risk,
            TelemetryService telemetry, InvestigationService investigations, MaintenanceService maintenance,
            ManualService manuals, AuditService audit, AgentAuditService agentAudit, MlPredictionService ml,
            com.basintelligence.users.AccessScopeService access, Clock clock) {
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
        this.clock = clock;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        List<Machine> all = machines.list();
        List<MachineCard> cards = new ArrayList<>();
        List<Alert> alerts = new ArrayList<>();
        for (Machine m : all) {
            Optional<RiskAssessment> r = risk.assess(m);
            List<AnomalyEvent> events = telemetry.anomalies(m.id(), Duration.ofHours(24), 500);
            DataQuality q = DataQuality.of(telemetry.recent(m.id(), Duration.ofMinutes(60)), clock.instant());
            boolean attention = m.healthStatus() != HealthStatus.NORMAL
                    || r.map(x -> x.level().ordinal() >= RiskLevel.HIGH.ordinal()).orElse(false)
                    || q.connectivity() != DataQuality.Connectivity.ONLINE;
            cards.add(new MachineCard(m, r, events.size(), q, attention));
            events.stream().filter(e -> e.detectedAt().isAfter(clock.instant().minus(Duration.ofHours(1))))
                    .forEach(e -> alerts.add(new Alert(m.code(), e)));
        }
        alerts.sort(Comparator.comparing((Alert a) -> a.event().detectedAt()).reversed());

        Map<RiskLevel, Long> byLevel = new EnumMap<>(RiskLevel.class);
        for (RiskLevel l : RiskLevel.values()) {
            byLevel.put(l, cards.stream().filter(c -> c.risk().map(x -> x.level() == l).orElse(false)).count());
        }
        List<MachineCard> assessed = cards.stream().filter(c -> c.risk().isPresent()).toList();
        RiskSummary summary = new RiskSummary(byLevel,
                assessed.stream().mapToDouble(c -> c.risk().get().probability()).average().orElse(0),
                assessed.stream().max(Comparator.comparingDouble(c -> c.risk().get().probability())).orElse(null),
                assessed.size());

        model.addAttribute("factory", currentFactory());
        model.addAttribute("cards", cards);
        model.addAttribute("total", cards.size());
        model.addAttribute("normal", cards.stream().filter(c -> !c.needsAttention()).count());
        model.addAttribute("attention", cards.stream().filter(MachineCard::needsAttention).count());
        model.addAttribute("alerts", alerts.stream().limit(10).toList());
        model.addAttribute("alertCount", alerts.size());
        model.addAttribute("criticalAlerts", alerts.stream().filter(a -> a.event().severity() == Severity.CRITICAL).count());
        model.addAttribute("riskSummary", summary);
        model.addAttribute("maintenanceEvents", maintenance.recent(8));
        model.addAttribute("machineCodes", all.stream().collect(Collectors.toMap(Machine::id, Machine::code)));
        model.addAttribute("investigations", investigations.recent(6));
        model.addAttribute("pendingReviews", investigations.pendingReviews());
        model.addAttribute("manualChunks", manuals.indexedChunks());
        return "dashboard";
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

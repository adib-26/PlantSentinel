package com.basintelligence.web.ui;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.Text;
import com.basintelligence.config.BasProperties;
import com.basintelligence.factory.FactoryService;
import com.basintelligence.investigation.InvestigationModels.InvestigationView;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.machine.Metric;
import com.basintelligence.machine.OperatingLimits;
import com.basintelligence.maintenance.MaintenanceService;
import com.basintelligence.risk.PredictionService;
import com.basintelligence.risk.RiskService;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.TelemetryRollupService;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.vision.InspectionService;
import com.basintelligence.vision.SampleImageGenerator;

import jakarta.validation.Valid;

/** Machine pages are addressed by machine code, e.g. /machines/CNC-01. */
@Controller
public class MachineWebController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.systemDefault());
    /** Windows longer than this are drawn from 5-minute rollups instead of raw readings. */
    private static final int RAW_LIMIT_MINUTES = 180;

    private final MachineService machines;
    private final FactoryService factories;
    private final TelemetryService telemetry;
    private final TelemetryRollupService rollups;
    private final RiskService risk;
    private final PredictionService predictions;
    private final MaintenanceService maintenance;
    private final InspectionService inspections;
    private final InvestigationService investigations;
    private final BasProperties properties;
    @org.springframework.beans.factory.annotation.Autowired
    private com.basintelligence.telemetry.SensorFaultService sensorFaults;
    @org.springframework.beans.factory.annotation.Autowired
    private com.basintelligence.failure.FailureEventService failures;
    @org.springframework.beans.factory.annotation.Autowired
    private com.basintelligence.telemetry.simulation.IncidentSimulator incidents;

    public MachineWebController(MachineService machines, FactoryService factories, TelemetryService telemetry,
            TelemetryRollupService rollups, RiskService risk, PredictionService predictions,
            MaintenanceService maintenance, InspectionService inspections, InvestigationService investigations,
            BasProperties properties) {
        this.machines = machines;
        this.factories = factories;
        this.telemetry = telemetry;
        this.rollups = rollups;
        this.risk = risk;
        this.predictions = predictions;
        this.maintenance = maintenance;
        this.inspections = inspections;
        this.investigations = investigations;
        this.properties = properties;
    }

    @ModelAttribute("machineTypes")
    MachineType[] machineTypes() {
        return MachineType.values();
    }

    @GetMapping("/machines/new")
    @PreAuthorize("hasRole('ENGINEER')")
    public String newMachine(Model model) {
        var allowed = machines.registrableFactories();
        model.addAttribute("form", new MachineService.RegisterMachine("", "", MachineType.CNC_MILL, "",
                allowed.isEmpty() ? FactoryService.DEFAULT_CODE : allowed.getFirst().code()));
        model.addAttribute("factories", allowed);
        return "machine-new";
    }

    @PostMapping("/machines")
    @PreAuthorize("hasRole('ENGINEER')")
    public String register(@Valid @ModelAttribute("form") MachineService.RegisterMachine form, BindingResult errors,
            Authentication auth, Model model, RedirectAttributes redirect) {
        model.addAttribute("factories", machines.registrableFactories());
        if (errors.hasErrors()) {
            return "machine-new";
        }
        try {
            Machine machine = machines.register(form, auth.getName());
            redirect.addFlashAttribute("flash", "Registered " + machine.code() + ". Point its BAS edge node at /api/edge/telemetry.");
            return "redirect:/machines/" + machine.code();
        } catch (InvalidStateException | IllegalArgumentException | com.basintelligence.common.NotFoundException e) {
            model.addAttribute("error", e.getMessage());
            return "machine-new";
        }
    }

    @GetMapping("/machines/{code}")
    public String detail(@PathVariable String code, Model model) {
        Machine machine = machines.getByCode(code);
        Duration window = properties.telemetry().analysisWindow();
        model.addAttribute("machine", machine);
        model.addAttribute("analysis", telemetry.analyze(machine, window));
        model.addAttribute("risk", risk.assess(machine).orElse(null));
        model.addAttribute("predictions", predictions.recent(machine.id(), 8));
        model.addAttribute("maintenance", maintenance.forMachine(machine.id(), 10));
        model.addAttribute("anomalies", telemetry.anomalies(machine.id(), Duration.ofHours(24), 25));
        model.addAttribute("inspections", inspections.recent(machine.id(), Duration.ofDays(30), 10));
        model.addAttribute("investigations", investigations.forMachine(machine.id(), 10));
        model.addAttribute("sampleKinds", SampleImageGenerator.Kind.values());
        model.addAttribute("metrics", Metric.values());
        model.addAttribute("windowMinutes", window.toMinutes());
        model.addAttribute("quality", com.basintelligence.analytics.DataQuality.of(
                telemetry.recent(machine.id(), Duration.ofMinutes(60)), Instant.now()));
        List<TelemetryReading> latest = new java.util.ArrayList<>(telemetry.latest(machine.id(), 25));
        java.util.Collections.reverse(latest);
        model.addAttribute("latestReadings", latest);
        model.addAttribute("sensorFaults", sensorFaults.recent(machine.id(), Duration.ofHours(24), 10));
        model.addAttribute("failures", failures.forMachine(machine.id(), 10));
        model.addAttribute("activeIncident", incidents.activeFor(machine.id()).orElse(null));
        model.addAttribute("incidents", incidents.forMachine(machine.id(), 5));
        model.addAttribute("incidentFaults", java.util.Arrays.stream(com.basintelligence.telemetry.simulation.Scenario.values())
                .filter(s -> s != com.basintelligence.telemetry.simulation.Scenario.HEALTHY).toList());
        model.addAttribute("now", Instant.now());
        return "machine-detail";
    }

    /**
     * The caller's machines with their health and current failure risk (the same assessment the overview cards
     * show), for the command palette and the Kiro ghost in the navigation bar. Scoped by {@link MachineService#list()}
     * like the overview, so it never lists another factory's machines.
     */
    @GetMapping(path = "/machines.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.PREDICTION)
    @ResponseBody
    public List<Map<String, Object>> machinesJson() {
        return machines.list().stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", m.code());
            row.put("name", m.name());
            row.put("type", m.type().displayName());
            row.put("location", m.location());
            row.put("health", m.healthStatus() == null ? "UNKNOWN" : m.healthStatus().name());
            row.put("lastSeenAt", m.lastSeenAt() == null ? null : m.lastSeenAt().toString());
            var assessment = risk.assess(m);
            row.put("risk", assessment.map(a -> a.level().name()).orElse(null));
            row.put("riskScore", assessment.map(a -> a.probability()).orElse(null));
            return row;
        }).toList();
    }

    /**
     * Chart data. Up to 3 hours uses raw readings; longer windows (up to 30 days) use 5-minute rollups so the
     * browser and database never move more than a few thousand points.
     */
    @GetMapping(path = "/machines/{code}/telemetry.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> telemetryJson(@PathVariable String code, @RequestParam(defaultValue = "120") int minutes) {
        Machine machine = machines.getByCode(code);
        int window = Math.clamp(minutes, 5, 43_200);
        Map<String, Object> series = new LinkedHashMap<>();
        List<String> labels;
        String resolution;
        if (window <= RAW_LIMIT_MINUTES) {
            List<TelemetryReading> readings = telemetry.recent(machine.id(), Duration.ofMinutes(window));
            labels = readings.stream().map(r -> TIME.format(r.recordedAt())).toList();
            for (Metric metric : Metric.values()) {
                series.put(metric.name(), readings.stream().mapToDouble(metric::of).toArray());
            }
            resolution = "raw";
        } else {
            List<TelemetryRollupService.Rollup> buckets = rollups.since(machine.id(),
                    Instant.now().minus(Duration.ofMinutes(window)));
            labels = buckets.stream().map(b -> DAY_TIME.format(b.bucketStart())).toList();
            series.put(Metric.TEMPERATURE.name(), buckets.stream().mapToDouble(TelemetryRollupService.Rollup::temperatureAvg).toArray());
            series.put(Metric.VIBRATION.name(), buckets.stream().mapToDouble(TelemetryRollupService.Rollup::vibrationAvg).toArray());
            series.put(Metric.RPM.name(), buckets.stream().mapToDouble(TelemetryRollupService.Rollup::rpmAvg).toArray());
            series.put(Metric.TORQUE.name(), buckets.stream().mapToDouble(TelemetryRollupService.Rollup::torqueAvg).toArray());
            series.put(Metric.PRESSURE.name(), buckets.stream().mapToDouble(TelemetryRollupService.Rollup::pressureAvg).toArray());
            resolution = "5-minute average";
        }
        Map<String, Object> limits = new LinkedHashMap<>();
        for (Metric metric : Metric.values()) {
            OperatingLimits l = machine.type().limits(metric);
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("label", metric.label() + " (" + metric.unit() + ")");
            lm.put("warnHigh", l.warnHigh());
            lm.put("critHigh", l.critHigh());
            lm.put("warnLow", Double.isNaN(l.warnLow()) ? null : l.warnLow());
            limits.put(metric.name(), lm);
        }
        return Map.of("labels", labels, "series", series, "limits", limits, "resolution", resolution);
    }

    @PostMapping("/machines/{code}/inspections")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.INSPECTION)
    @PreAuthorize("hasRole('ENGINEER')")
    public String upload(@PathVariable String code, @RequestParam String component, @RequestParam("image") MultipartFile image,
            Authentication auth, RedirectAttributes redirect) throws IOException {
        if (image.isEmpty()) {
            throw new IllegalArgumentException("Choose an image to inspect");
        }
        Machine machine = machines.getByCode(code);
        var result = inspections.inspect(machine, cleanComponent(component), image.getOriginalFilename(), image.getBytes(),
                auth.getName());
        redirect.addFlashAttribute("flash", "Inspection complete: " + result.verdict() + " (" + Text.pct(result.defectProbability()) + ")");
        return "redirect:/machines/" + machine.code() + "#inspections";
    }

    @PostMapping("/machines/{code}/inspections/sample")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.INSPECTION)
    @PreAuthorize("hasRole('ENGINEER')")
    public String sample(@PathVariable String code, @RequestParam String component, @RequestParam SampleImageGenerator.Kind kind,
            Authentication auth, RedirectAttributes redirect) {
        Machine machine = machines.getByCode(code);
        var result = inspections.inspectSample(machine, cleanComponent(component), kind, auth.getName());
        redirect.addFlashAttribute("flash", "Sample " + kind + " inspected: " + result.verdict() + " ("
                + Text.pct(result.defectProbability()) + ")");
        return "redirect:/machines/" + machine.code() + "#inspections";
    }

    @GetMapping(path = "/inspections/{id}/annotated.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> annotated(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(inspections.annotatedImage(id));
    }

    @PostMapping("/machines/{code}/incidents")
    @PreAuthorize("hasRole('ENGINEER')")
    public String simulateIncident(@PathVariable String code,
            @RequestParam com.basintelligence.telemetry.simulation.Scenario fault,
            @RequestParam(defaultValue = "5") int rampMinutes, Authentication auth, RedirectAttributes redirect) {
        var incident = incidents.start(code, fault, rampMinutes, auth.getName());
        redirect.addFlashAttribute("flash", "Simulated incident SIM-" + incident.id() + " started: " + fault
                + " ramps to full severity in " + rampMinutes + " min, then an investigation starts automatically.");
        return "redirect:/machines/" + incident.machineCode() + "#incident";
    }

    @PostMapping("/incidents/{id}/stop")
    @PreAuthorize("hasRole('ENGINEER')")
    public String stopIncident(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        var incident = incidents.stop(id, auth.getName());
        redirect.addFlashAttribute("flash", "Simulated incident SIM-" + id + " stopped; normal simulation resumes.");
        return "redirect:/machines/" + incident.machineCode() + "#incident";
    }

    @PostMapping("/machines/{code}/investigations")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.INVESTIGATION)
    @PreAuthorize("hasRole('ENGINEER')")
    public String investigate(@PathVariable String code, @RequestParam(required = false) String question, Authentication auth) {
        InvestigationView view = investigations.investigate(machines.getByCode(code).id(),
                question == null || question.isBlank() ? null : question.strip(), auth.getName());
        return "redirect:/investigations/" + view.investigation().id();
    }

    private static String cleanComponent(String component) {
        if (component == null || component.isBlank()) {
            throw new IllegalArgumentException("Component name is required");
        }
        return Text.truncate(component.strip(), 120);
    }
}

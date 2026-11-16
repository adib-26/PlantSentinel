package com.basintelligence.web.ui;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.basintelligence.analytics.TelemetryAnalysis;
import com.basintelligence.config.BasProperties;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.ml.MlPredictionService;
import com.basintelligence.risk.PredictionService;
import com.basintelligence.risk.RiskAssessment;
import com.basintelligence.risk.RiskService;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.vision.InspectionService;

/** Screen 3, predictive analytics: anomaly scores, risk estimates, model performance, importance, history, versions. */
@Controller
public class AnalyticsController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.systemDefault());

    public record MachineAnalytics(Machine machine, TelemetryAnalysis.MultivariateResult multivariate,
            Optional<RiskAssessment> risk, List<PredictionService.Prediction> recentPredictions, String trend) {
    }

    private final MachineService machines;
    private final TelemetryService telemetry;
    private final RiskService risk;
    private final PredictionService predictions;
    private final MlPredictionService ml;
    private final InspectionService inspections;
    private final BasProperties properties;

    public AnalyticsController(MachineService machines, TelemetryService telemetry, RiskService risk,
            PredictionService predictions, MlPredictionService ml, InspectionService inspections, BasProperties properties) {
        this.machines = machines;
        this.telemetry = telemetry;
        this.risk = risk;
        this.predictions = predictions;
        this.ml = ml;
        this.inspections = inspections;
        this.properties = properties;
    }

    @GetMapping("/analytics")
    public String analytics(@RequestParam(required = false) String machine, Model model) {
        List<MachineAnalytics> rows = new ArrayList<>();
        for (Machine m : machines.list()) {
            var analysis = telemetry.analyze(m, properties.telemetry().analysisWindow());
            var history = predictions.recent(m.id(), 2);
            String trend = history.size() < 2 ? "–"
                    : history.get(0).probability() > history.get(1).probability() + 0.02 ? "rising"
                    : history.get(0).probability() < history.get(1).probability() - 0.02 ? "falling" : "steady";
            rows.add(new MachineAnalytics(m, analysis.multivariate(), risk.assess(m), history, trend));
        }
        MachineAnalytics selected = rows.stream().filter(r -> r.machine().code().equalsIgnoreCase(machine == null ? "" : machine))
                .findFirst()
                .orElse(rows.stream().filter(r -> r.risk().isPresent())
                        .max(Comparator.comparingDouble(r -> r.risk().get().probability())).orElse(rows.isEmpty() ? null : rows.getFirst()));
        model.addAttribute("rows", rows);
        model.addAttribute("selected", selected);
        model.addAttribute("history", selected == null ? List.of() : predictions.recent(selected.machine().id(), 20));
        model.addAttribute("riskModel", risk.modelInfo());
        model.addAttribute("weights", risk.featureWeights());
        model.addAttribute("maxWeight", risk.featureWeights().stream().mapToDouble(w -> Math.abs(w.weight())).max().orElse(1));
        model.addAttribute("intercept", risk.intercept());
        model.addAttribute("labCards", ml.models());
        model.addAttribute("defectModel", inspections.modelVersion());
        model.addAttribute("windowMinutes", properties.telemetry().analysisWindow().toMinutes());
        return "analytics";
    }

    @GetMapping(path = "/analytics/{code}/predictions.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> predictionHistory(@PathVariable String code) {
        Machine m = machines.getByCode(code);
        List<PredictionService.Prediction> list = new ArrayList<>(predictions.recent(m.id(), 200));
        java.util.Collections.reverse(list);
        return Map.of("labels", list.stream().map(p -> TIME.format(p.predictedAt())).toList(),
                "probability", list.stream().mapToDouble(PredictionService.Prediction::probability).toArray(),
                "trigger", list.stream().map(p -> p.trigger().name()).toList());
    }

    @GetMapping("/analytics/{code}/predictions/{id}")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.PREDICTION)
    public String predictionVerification(@PathVariable String code, @PathVariable long id, Model model) {
        Machine m = machines.getByCode(code);
        model.addAttribute("machine", m);
        model.addAttribute("verification", predictions.verify(m, id));
        model.addAttribute("retention", properties.telemetry().retention().toDays());
        return "prediction-verification";
    }

    static Duration window(BasProperties p) {
        return p.telemetry().analysisWindow();
    }
}

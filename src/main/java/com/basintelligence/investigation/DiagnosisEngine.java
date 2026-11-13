package com.basintelligence.investigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.basintelligence.analytics.AnomalyEvent;
import com.basintelligence.analytics.TelemetryAnalysis;
import com.basintelligence.analytics.TelemetryAnalysis.MetricSummary;
import com.basintelligence.common.Text;
import com.basintelligence.investigation.InvestigationModels.Priority;
import com.basintelligence.machine.Metric;
import com.basintelligence.machine.Severity;
import com.basintelligence.risk.RiskAssessment;
import com.basintelligence.risk.RiskLevel;
import com.basintelligence.vision.Inspection;

/**
 * Deterministic fault-signature rules that turn analytics into ranked, explainable hypotheses. This is the
 * offline reasoning path. When Kiro investigates over MCP, it sees the same evidence and can reason further,
 * but it can only propose actions, not approve them.
 */
public final class DiagnosisEngine {

    public record Input(TelemetryAnalysis analysis, List<AnomalyEvent> anomalies, Optional<RiskAssessment> risk,
            List<Inspection> inspections) {
    }

    public record Hypothesis(String code, String title, double confidence, List<String> signals, Set<Metric> metrics,
            boolean usesInspection, String action, String manualQuery) {
    }

    public record Diagnosis(List<Hypothesis> hypotheses, Priority priority) {

        public Hypothesis primary() {
            return hypotheses.getFirst();
        }
    }

    public Diagnosis diagnose(Input in) {
        List<Hypothesis> hypotheses = new ArrayList<>();
        TelemetryAnalysis a = in.analysis();
        boolean multivariate = a.multivariate() != null && a.multivariate().anomalous();

        Optional<MetricSummary> vib = a.metric(Metric.VIBRATION);
        Optional<MetricSummary> temp = a.metric(Metric.TEMPERATURE);
        Optional<MetricSummary> torque = a.metric(Metric.TORQUE);
        Optional<MetricSummary> pressure = a.metric(Metric.PRESSURE);
        Optional<MetricSummary> rpm = a.metric(Metric.RPM);

        boolean vibElevated = vib.map(v -> ratio(v) >= 1.5 || (v.changePct() >= 0.3 && v.slopePerHour() > 0)).orElse(false);
        boolean tempElevated = temp.map(t -> ratio(t) >= 1.15 || t.changePct() >= 0.1).orElse(false);
        boolean torqueElevated = torque.map(t -> ratio(t) >= 1.25 || t.changePct() >= 0.15).orElse(false);

        if (vibElevated) {
            MetricSummary v = vib.get();
            List<String> signals = new ArrayList<>();
            signals.add(trend(v));
            double c = 0.45;
            if (v.latestSeverity() != Severity.NORMAL) {
                c += 0.2;
                signals.add("vibration is in the " + v.latestSeverity().name().toLowerCase() + " zone");
            }
            if (temp.isPresent() && (temp.get().changePct() >= 0.04 || temp.get().slopePerHour() > 0.5)) {
                c += 0.15;
                signals.add("temperature is rising with it (" + trend(temp.get()) + "), consistent with friction heat");
            }
            if (multivariate) {
                c += 0.1;
                signals.add("multivariate Isolation Forest flags the current operating state as abnormal");
            }
            hypotheses.add(new Hypothesis("BEARING_WEAR", "Rolling-element bearing wear", Math.min(c, 0.95), signals,
                    Set.of(Metric.VIBRATION, Metric.TEMPERATURE), false,
                    "Schedule a bearing inspection within the next shift: take vibration spectra at the drive-end and "
                            + "non-drive-end bearings, check lubrication condition and quantity, and replace the bearing "
                            + "if defect frequencies or damage are confirmed.",
                    "bearing wear vibration increase lubrication grease replace bearing temperature rise"));
        }
        if (tempElevated && !vibElevated) {
            MetricSummary t = temp.get();
            List<String> signals = new ArrayList<>(List.of(trend(t), "vibration remains near normal"));
            double c = 0.5 + (t.latestSeverity() != Severity.NORMAL ? 0.2 : 0) + (t.slopePerHour() > 0 ? 0.1 : 0);
            hypotheses.add(new Hypothesis("COOLING_DEGRADATION", "Cooling system degradation", Math.min(c, 0.9), signals,
                    Set.of(Metric.TEMPERATURE), false,
                    "Inspect the cooling circuit: clean cooler or heat-exchanger fins, verify fan operation, coolant or "
                            + "oil level and flow, and check the thermostatic valve before the high-temperature trip is reached.",
                    "overheating high temperature cooling fan cooler clean coolant oil flow thermostatic valve"));
        }
        if (torqueElevated) {
            MetricSummary t = torque.get();
            List<String> signals = new ArrayList<>(List.of(trend(t)));
            double c = 0.5;
            if (rpm.isPresent() && rpm.get().changePct() < -0.03) {
                c += 0.2;
                signals.add("speed is sagging under load (" + trend(rpm.get()) + ")");
            }
            if (t.latestSeverity() != Severity.NORMAL) {
                c += 0.15;
            }
            hypotheses.add(new Hypothesis("MECHANICAL_OVERLOAD", "Mechanical overload or binding", Math.min(c, 0.9),
                    signals, Set.of(Metric.TORQUE, Metric.RPM), false,
                    "Stop at the next safe opportunity and check for mechanical binding, jams or excessive load; inspect "
                            + "the coupling, belt tension and gearbox, and compare motor current with the nameplate rating.",
                    "high torque overload jam binding coupling belt gearbox motor current"));
        }
        pressure.filter(p -> ratio(p) <= 0.85 || p.changePct() <= -0.12).ifPresent(p -> {
            double c = 0.55 + (p.latestSeverity() != Severity.NORMAL ? 0.2 : 0);
            hypotheses.add(new Hypothesis("PRESSURE_LOSS", "Leak or pressure loss", Math.min(c, 0.9),
                    List.of(trend(p)), Set.of(Metric.PRESSURE), false,
                    "Perform a leak check on seals, hoses and fittings, verify filter differential pressure and the "
                            + "pressure regulator, and repair any leak found.",
                    "pressure drop leak seal hose fitting filter regulator"));
        });
        if (!torqueElevated) {
            rpm.filter(r -> Math.abs(ratio(r) - 1) >= 0.08).ifPresent(r -> hypotheses.add(new Hypothesis(
                    "SPEED_CONTROL_FAULT", "Speed control or drive fault", 0.5, List.of(trend(r)), Set.of(Metric.RPM), false,
                    "Check the variable-frequency drive parameters and fault log, the speed encoder, and belt slip.",
                    "speed fluctuation drive inverter encoder belt slip")));
        }
        in.inspections().stream()
                .filter(i -> i.verdict() == Inspection.Verdict.DEFECT)
                .max(Comparator.comparingDouble(Inspection::defectProbability))
                .ifPresent(i -> hypotheses.add(new Hypothesis("SURFACE_DEFECT", "Component surface defect (" + i.component() + ")",
                        // A screening result alone is weaker evidence than a sustained telemetry trend.
                        Math.min(0.35 + 0.4 * i.defectProbability(), 0.75),
                        List.of("visual inspection of " + i.component() + " classified it as DEFECT ("
                                + Text.pct(i.defectProbability()) + ", " + i.defectRegions() + " region(s))"),
                        Set.of(), true,
                        "Quarantine the affected component and confirm the indication with dye-penetrant or magnetic-particle "
                                + "testing; replace it if a crack is confirmed and investigate the root cause.",
                        "crack surface defect component inspection dye penetrant replace")));
        corroborateBearingWithDefect(hypotheses);

        if (hypotheses.isEmpty()) {
            if (multivariate) {
                hypotheses.add(new Hypothesis("UNCLASSIFIED_DEVIATION", "Unclassified multivariate deviation", 0.35,
                        List.of("the combination of channels is unusual although no single channel matches a known fault signature"),
                        Set.of(), false,
                        "Increase the monitoring frequency and perform a general walk-around inspection on the next shift.",
                        "abnormal operation troubleshooting general inspection"));
            } else {
                hypotheses.add(new Hypothesis("NO_FAULT", "No fault signature detected", 0.8,
                        List.of("all channels are within their operating envelopes and stable"), Set.of(), false,
                        "Continue routine monitoring and follow the preventive maintenance schedule.",
                        "preventive maintenance schedule routine inspection"));
            }
        }
        hypotheses.sort(Comparator.comparingDouble(Hypothesis::confidence).reversed());
        return new Diagnosis(List.copyOf(hypotheses), priority(in, hypotheses));
    }

    /**
     * A cracked component next to a worn bearing is mutually reinforcing: bearing damage loads the housing, and a
     * crack can be the visible consequence. Raise bearing-wear confidence and record the corroboration.
     */
    private static void corroborateBearingWithDefect(List<Hypothesis> hypotheses) {
        boolean defect = hypotheses.stream().anyMatch(h -> h.code().equals("SURFACE_DEFECT"));
        for (int i = 0; i < hypotheses.size(); i++) {
            Hypothesis h = hypotheses.get(i);
            if (defect && h.code().equals("BEARING_WEAR")) {
                List<String> signals = new ArrayList<>(h.signals());
                signals.add("a visual inspection found a defect on a nearby component, consistent with mechanical damage");
                hypotheses.set(i, new Hypothesis(h.code(), h.title(), Math.min(h.confidence() + 0.1, 0.95), signals,
                        h.metrics(), true, h.action(), h.manualQuery()));
            }
        }
    }

    private static Priority priority(Input in, List<Hypothesis> hypotheses) {
        boolean critical = in.analysis().metrics().stream().anyMatch(m -> m.latestSeverity() == Severity.CRITICAL)
                || in.risk().map(r -> r.level() == RiskLevel.CRITICAL).orElse(false);
        if (critical) {
            return Priority.URGENT;
        }
        boolean warning = in.analysis().metrics().stream().anyMatch(m -> m.latestSeverity() == Severity.WARNING)
                || in.risk().map(r -> r.level() == RiskLevel.HIGH).orElse(false);
        if (warning) {
            return Priority.HIGH;
        }
        boolean fault = !hypotheses.getFirst().code().equals("NO_FAULT");
        if (fault || in.risk().map(r -> r.level() == RiskLevel.MEDIUM).orElse(false)) {
            return Priority.MEDIUM;
        }
        return Priority.LOW;
    }

    private static double ratio(MetricSummary m) {
        return m.recentMean() / m.nominal();
    }

    static String trend(MetricSummary m) {
        return "%s %s %s now vs %s at the start of the window (%s%s, %s%s/h)".formatted(
                m.metric().label().toLowerCase(), Text.num(m.recentMean(), 2), m.unit(), Text.num(m.baselineMean(), 2),
                m.changePct() >= 0 ? "+" : "", Text.pct(m.changePct()),
                m.slopePerHour() >= 0 ? "+" : "", Text.num(m.slopePerHour(), 2));
    }
}

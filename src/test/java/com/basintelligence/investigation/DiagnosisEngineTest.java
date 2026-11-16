package com.basintelligence.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.basintelligence.TestReadings;
import com.basintelligence.analytics.TelemetryAnalysis;
import com.basintelligence.analytics.TelemetryAnalyzer;
import com.basintelligence.investigation.DiagnosisEngine.Hypothesis;
import com.basintelligence.investigation.InvestigationModels.Priority;
import com.basintelligence.machine.MachineType;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.vision.Inspection;

class DiagnosisEngineTest {

    private final DiagnosisEngine engine = new DiagnosisEngine();
    private final TelemetryAnalyzer analyzer = new TelemetryAnalyzer();

    @Test
    void bearingWearIsRecognised() {
        var d = engine.diagnose(input(MachineType.CNC_MILL, Scenario.BEARING_WEAR, List.of()));

        assertThat(d.primary().code()).isEqualTo("BEARING_WEAR");
        assertThat(d.primary().signals()).anyMatch(s -> s.contains("vibration"));
        assertThat(d.priority()).isIn(Priority.HIGH, Priority.URGENT);
    }

    @Test
    void coolingDegradationIsRecognised() {
        var d = engine.diagnose(input(MachineType.AIR_COMPRESSOR, Scenario.COOLING_DEGRADATION, List.of()));

        assertThat(d.primary().code()).isEqualTo("COOLING_DEGRADATION");
    }

    @Test
    void pressureLeakIsRecognised() {
        var d = engine.diagnose(input(MachineType.CENTRIFUGAL_PUMP, Scenario.PRESSURE_LEAK, List.of()));

        assertThat(d.hypotheses()).extracting(Hypothesis::code).contains("PRESSURE_LOSS");
    }

    @Test
    void overloadIsRecognised() {
        var d = engine.diagnose(input(MachineType.CONVEYOR, Scenario.OVERLOAD, List.of()));

        assertThat(d.primary().code()).isEqualTo("MECHANICAL_OVERLOAD");
    }

    @Test
    void healthyMachineHasNoFaultAndLowPriority() {
        var d = engine.diagnose(input(MachineType.CENTRIFUGAL_PUMP, Scenario.HEALTHY, List.of()));

        assertThat(d.primary().code()).isEqualTo("NO_FAULT");
        assertThat(d.priority()).isEqualTo(Priority.LOW);
    }

    @Test
    void defectInspectionCorroboratesBearingWear() {
        var defect = new Inspection(1, TestReadings.MACHINE, Instant.now(), "bearing housing", "x.png", "a", "b", 0.97,
                Inspection.Verdict.DEFECT, 1, "m", 3, "engineer", null);
        var withDefect = engine.diagnose(input(MachineType.CNC_MILL, Scenario.BEARING_WEAR, List.of(defect)));
        var without = engine.diagnose(input(MachineType.CNC_MILL, Scenario.BEARING_WEAR, List.of()));

        assertThat(withDefect.primary().code()).isEqualTo("BEARING_WEAR");
        assertThat(withDefect.primary().confidence()).isGreaterThan(without.primary().confidence());
        assertThat(withDefect.hypotheses()).extracting(Hypothesis::code).contains("SURFACE_DEFECT");
    }

    private DiagnosisEngine.Input input(MachineType type, Scenario scenario, List<Inspection> inspections) {
        TelemetryAnalysis analysis = analyzer.analyze("M-1", type, TestReadings.series(type, scenario, 120, 11));
        return new DiagnosisEngine.Input(analysis, List.of(), Optional.empty(), inspections);
    }
}

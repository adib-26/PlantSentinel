package com.basintelligence.telemetry.simulation;

import java.util.List;

import com.basintelligence.machine.MachineType;

/** The demo plant: four machines, two of which are developing faults. */
public final class DemoScenarios {

    public record DemoMachine(String code, String name, MachineType type, String location, Scenario scenario) {
    }

    public static final List<DemoMachine> PLANT = List.of(
            new DemoMachine("CNC-01", "Spindle Mill A", MachineType.CNC_MILL, "Hall 1 / Cell 3", Scenario.BEARING_WEAR),
            new DemoMachine("PUMP-02", "Coolant Pump 2", MachineType.CENTRIFUGAL_PUMP, "Hall 1 / Utility", Scenario.HEALTHY),
            new DemoMachine("COMP-03", "Main Air Compressor", MachineType.AIR_COMPRESSOR, "Compressor House",
                    Scenario.COOLING_DEGRADATION),
            new DemoMachine("CONV-04", "Packing Line Conveyor", MachineType.CONVEYOR, "Hall 2 / Line 1", Scenario.HEALTHY));

    private DemoScenarios() {
    }

    public static Scenario forCode(String code) {
        return PLANT.stream().filter(m -> m.code().equals(code)).map(DemoMachine::scenario).findFirst()
                .orElse(Scenario.HEALTHY);
    }
}

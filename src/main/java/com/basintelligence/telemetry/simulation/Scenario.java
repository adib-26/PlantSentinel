package com.basintelligence.telemetry.simulation;

/** Fault scenarios the simulator can play out on a machine. */
public enum Scenario {
    HEALTHY,
    /** Rolling-element bearing degradation: vibration climbs, followed by friction heat. */
    BEARING_WEAR,
    /** Fouled heat exchanger or failing fan: temperature climbs on its own. */
    COOLING_DEGRADATION,
    /** Seal or line leak: pressure decays, speed and torque compensate slightly. */
    PRESSURE_LEAK,
    /** Jammed or overloaded drive: torque rises and speed sags. */
    OVERLOAD
}

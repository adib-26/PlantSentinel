package com.basintelligence.machine;

import static com.basintelligence.machine.OperatingLimits.band;
import static com.basintelligence.machine.OperatingLimits.highOnly;

import java.util.EnumMap;
import java.util.Map;

/**
 * Supported machine classes and their operating envelopes. Vibration limits follow the
 * zone boundaries commonly used for ISO 10816-3 class machines (zone C at 4.5 mm/s, zone D at 7.1 mm/s).
 */
public enum MachineType {
    CNC_MILL("CNC milling machine",
            highOnly(55, 75, 90),
            highOnly(1.8, 4.5, 7.1),
            band(2400, 1800, 1200, 3200, 3600),
            highOnly(35, 55, 70),
            band(6.0, 4.5, 3.5, 8.0, 9.0)),
    CENTRIFUGAL_PUMP("Centrifugal pump",
            highOnly(45, 65, 80),
            highOnly(2.0, 4.5, 7.1),
            band(2950, 2600, 2300, 3100, 3200),
            highOnly(50, 70, 85),
            band(4.0, 3.0, 2.2, 5.5, 6.5)),
    AIR_COMPRESSOR("Screw air compressor",
            highOnly(70, 90, 105),
            highOnly(2.5, 5.0, 8.0),
            band(1480, 1350, 1200, 1550, 1600),
            highOnly(95, 120, 140),
            band(7.5, 6.5, 5.5, 8.5, 9.5)),
    CONVEYOR("Belt conveyor drive",
            highOnly(40, 60, 75),
            highOnly(1.2, 3.0, 5.0),
            band(1450, 1200, 900, 1550, 1650),
            highOnly(25, 40, 50),
            band(5.0, 4.0, 3.0, 6.5, 7.5));

    private final String displayName;
    private final Map<Metric, OperatingLimits> limits = new EnumMap<>(Metric.class);

    MachineType(String displayName, OperatingLimits temperature, OperatingLimits vibration, OperatingLimits rpm,
            OperatingLimits torque, OperatingLimits pressure) {
        this.displayName = displayName;
        limits.put(Metric.TEMPERATURE, temperature);
        limits.put(Metric.VIBRATION, vibration);
        limits.put(Metric.RPM, rpm);
        limits.put(Metric.TORQUE, torque);
        limits.put(Metric.PRESSURE, pressure);
    }

    public String displayName() {
        return displayName;
    }

    public OperatingLimits limits(Metric metric) {
        return limits.get(metric);
    }
}

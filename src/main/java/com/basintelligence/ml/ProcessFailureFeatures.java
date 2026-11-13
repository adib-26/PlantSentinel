package com.basintelligence.ml;

import java.util.List;

/**
 * Feature definitions for the AI4I 2020 process-failure model. Shared by the ML lab (training) and the prediction
 * API (inference) so both compute features identically.
 * <p>
 * Excluded on purpose: UDI and Product ID (identifiers), and the five failure-mode flags TWF, HDF, PWF, OSF, RNF
 * (they are the label broken down by cause, so using them would leak the answer).
 */
public final class ProcessFailureFeatures {

    public static final List<ModelCard.Feature> DEFINITIONS = List.of(
            new ModelCard.Feature("type_l", "0/1", "Product quality variant is L (low)"),
            new ModelCard.Feature("type_m", "0/1", "Product quality variant is M (medium); H is the reference level"),
            new ModelCard.Feature("air_temp_k", "K", "Air temperature"),
            new ModelCard.Feature("process_temp_k", "K", "Process temperature"),
            new ModelCard.Feature("temp_diff_k", "K", "Process minus air temperature (heat-dissipation margin)"),
            new ModelCard.Feature("rpm", "rpm", "Rotational speed"),
            new ModelCard.Feature("torque_nm", "N·m", "Torque"),
            new ModelCard.Feature("tool_wear_min", "min", "Accumulated tool wear"),
            new ModelCard.Feature("power_w", "W", "Mechanical power = torque × rpm × 2π / 60"),
            new ModelCard.Feature("strain_min_nm", "min·N·m", "Tool wear × torque (overstrain indicator)"));

    public static final String[] NAMES = DEFINITIONS.stream().map(ModelCard.Feature::name).toArray(String[]::new);

    private ProcessFailureFeatures() {
    }

    public static double[] of(char type, double airTempK, double processTempK, double rpm, double torqueNm,
            double toolWearMin) {
        char t = Character.toUpperCase(type);
        if (t != 'L' && t != 'M' && t != 'H') {
            throw new IllegalArgumentException("type must be L, M or H");
        }
        return new double[] {
                t == 'L' ? 1 : 0,
                t == 'M' ? 1 : 0,
                airTempK,
                processTempK,
                processTempK - airTempK,
                rpm,
                torqueNm,
                toolWearMin,
                torqueNm * rpm * 2 * Math.PI / 60,
                toolWearMin * torqueNm
        };
    }
}

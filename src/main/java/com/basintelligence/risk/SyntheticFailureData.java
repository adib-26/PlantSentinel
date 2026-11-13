package com.basintelligence.risk;

import java.util.Random;

/**
 * Labelled training data for the failure-risk model, generated from a physics-inspired hazard function.
 * <p>
 * Public run-to-failure datasets (e.g. AI4I 2020, NASA C-MAPSS) use different channels and machines, so the
 * baseline model is trained on synthetic operating states instead: 65% healthy states with sensor noise and
 * 35% states with one injected fault mode at random severity. The failure label is drawn from a hazard that
 * combines the fault signatures, including a heat-vibration interaction typical of bearing failure.
 * Replace this generator with real maintenance history when it becomes available. The feature contract
 * in {@link RiskFeatures} stays the same.
 */
public final class SyntheticFailureData {

    public record Dataset(double[][] x, int[] y) {
    }

    private SyntheticFailureData() {
    }

    public static Dataset generate(int samples, long seed) {
        Random random = new Random(seed);
        double[][] x = new double[samples][];
        int[] y = new int[samples];
        for (int i = 0; i < samples; i++) {
            double temp = 1 + random.nextGaussian() * 0.04;
            double vib = Math.exp(random.nextGaussian() * 0.15);
            double torque = 1 + random.nextGaussian() * 0.06;
            double pressure = 1 + random.nextGaussian() * 0.04;
            double speed = 1 + random.nextGaussian() * 0.02;
            if (random.nextDouble() < 0.35) {
                double severity = random.nextDouble();
                switch (random.nextInt(5)) {
                    case 0 -> temp += 0.6 * severity;                               // cooling failure
                    case 1 -> { vib *= 1 + 4 * severity; temp += 0.3 * severity * severity; } // bearing wear
                    case 2 -> { torque *= 1 + 0.9 * severity; speed -= 0.15 * severity; }      // overload
                    case 3 -> pressure -= 0.55 * severity;                          // leak
                    default -> speed += (random.nextBoolean() ? 1 : -1) * 0.3 * severity; // drive/control fault
                }
            }
            double[] f = RiskFeatures.fromRatios(temp, vib, torque, pressure, speed);
            // Calibrated so that, e.g., vibration at 2x nominal with a mild temperature rise gives roughly 50-70%
            // risk, and vibration at 3x nominal (ISO zone C/D for most machines) gives above 90%.
            double hazard = 4.0 * f[0] + 0.6 * f[1] + 3.0 * f[2] + 4.5 * f[3] + 6.0 * f[4] + 1.0 * f[5]
                    + 1.5 * f[0] * f[1];
            double probability = 1 / (1 + Math.exp(-4 * (hazard - 1.0)));
            x[i] = f;
            y[i] = random.nextDouble() < probability ? 1 : 0;
        }
        return new Dataset(x, y);
    }
}

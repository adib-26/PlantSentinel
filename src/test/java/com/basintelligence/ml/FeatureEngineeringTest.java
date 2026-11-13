package com.basintelligence.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class FeatureEngineeringTest {

    @Test
    void processFeaturesDerivePowerStrainAndTemperatureMargin() {
        double[] f = ProcessFailureFeatures.of('L', 300, 310, 1500, 40, 100);

        assertThat(f).hasSize(ProcessFailureFeatures.NAMES.length);
        assertThat(f[0]).isEqualTo(1); // type L
        assertThat(f[1]).isEqualTo(0);
        assertThat(f[4]).isEqualTo(10); // temperature difference
        assertThat(f[8]).isCloseTo(40 * 1500 * 2 * Math.PI / 60, within(1e-9)); // power, W
        assertThat(f[9]).isEqualTo(4000); // strain
        assertThatThrownBy(() -> ProcessFailureFeatures.of('X', 300, 310, 1500, 40, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rulFeaturesUseCurrentValueWindowMeanAndSlope() {
        List<double[]> history = new ArrayList<>();
        for (int c = 0; c < 15; c++) {
            double[] s = new double[22];
            for (int k = 1; k <= 21; k++) s[k] = 100 + 2 * c; // every sensor rises by 2 per cycle
            history.add(s);
        }
        double[] f = RulFeatures.of(15, history);

        assertThat(f).hasSize(RulFeatures.names().length);
        assertThat(f[0]).isEqualTo(15);
        assertThat(f[1]).isEqualTo(128); // current
        assertThat(f[2]).isCloseTo(119, within(1e-9)); // mean of cycles 5..14 = 110..128
        assertThat(f[3]).isCloseTo(2, within(1e-9)); // slope
    }

    @Test
    void bearingFeaturesSeparateImpulsiveFromSmoothVibration() {
        double[] sine = new double[BearingFeatures.WINDOW];
        double[] impacts = new double[BearingFeatures.WINDOW];
        for (int i = 0; i < sine.length; i++) {
            sine[i] = Math.sin(2 * Math.PI * 500 * i / BearingFeatures.SAMPLE_RATE_HZ);
            impacts[i] = 0.05 * sine[i] + (i % 100 == 0 ? 3 : 0);
        }
        double[] smooth = BearingFeatures.of(sine);
        double[] impulsive = BearingFeatures.of(impacts);

        assertThat(smooth[0]).isCloseTo(Math.sqrt(0.5), within(0.01)); // RMS of a unit sine
        assertThat(smooth[3]).isCloseTo(1.5, within(0.05)); // kurtosis of a sine
        assertThat(impulsive[3]).isGreaterThan(20); // impacts are highly impulsive
        assertThat(smooth[7]).isGreaterThan(0.95); // 500 Hz energy lies in the 0-1 kHz band
        assertThatThrownBy(() -> BearingFeatures.of(new double[10])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fftMatchesTheDefinitionOfTheDft() {
        double[] re = {1, 2, 3, 4, 0, 0, 0, 0}, im = new double[8];
        BearingFeatures.fft(re, im);

        assertThat(re[0]).isCloseTo(10, within(1e-9));
        assertThat(re[4]).isCloseTo(-2, within(1e-9)); // 1 - 2 + 3 - 4
    }
}

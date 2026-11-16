package com.basintelligence.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.basintelligence.TestReadings;
import com.basintelligence.machine.MachineType;
import com.basintelligence.risk.PredictionVerification.Outcome;
import com.basintelligence.risk.PredictionVerification.Status;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.simulation.Scenario;

class PredictionVerifierTest {

    private static final MachineType TYPE = MachineType.CNC_MILL;
    private static final List<TelemetryReading> READINGS = TestReadings.series(TYPE, Scenario.BEARING_WEAR, 12, 3);
    private static final Duration RETENTION = Duration.ofDays(7);
    /** Retention cutoff while the readings are still kept, and after they have been pruned. */
    private static final Instant KEPT = TestReadings.T0.minus(Duration.ofDays(1));
    private static final Instant PRUNED = TestReadings.T0.plus(Duration.ofDays(1));

    private static FailureRiskModel model;
    private static double[] features;
    private static RiskAssessment.InputReference input;

    @BeforeAll
    static void recordAPrediction() {
        model = FailureRiskModel.train(2000, 42);
        var means = RiskFeatures.means(READINGS);
        features = RiskFeatures.fromMeans(TYPE, means);
        input = new RiskAssessment.InputReference(READINGS.getFirst().recordedAt(), READINGS.getLast().recordedAt(),
                READINGS.size(), means, RiskFeatures.sha256(features));
    }

    @Test
    void intactPredictionWithItsRawReadingsPassesEveryCheck() {
        var v = verify(stored().build());

        assertThat(v.status()).isEqualTo(Status.VERIFIED);
        assertThat(v.checks()).extracting(PredictionVerification.Check::name).containsExactly(
                PredictionVerifier.RISK_CLASS, PredictionVerifier.INPUT_RECORD, PredictionVerifier.FEATURE_DIGEST,
                PredictionVerifier.FEATURE_ENGINEERING, PredictionVerifier.MODEL_RESCORE, PredictionVerifier.RAW_TELEMETRY);
        assertThat(v.checks()).allSatisfy(c -> assertThat(c.outcome()).as(c.name()).isEqualTo(Outcome.PASS));
    }

    @Test
    void afterRetentionPrunesTheReadingsTheRecordedInputStillVerifies() {
        var v = verify(stored().window(List.of()).retainedSince(PRUNED).build());

        assertThat(v.status()).isEqualTo(Status.VERIFIED);
        assertThat(outcome(v, PredictionVerifier.RAW_TELEMETRY)).isEqualTo(Outcome.SKIPPED);
        assertThat(detail(v, PredictionVerifier.RAW_TELEMETRY)).contains("pruned", "kept 7 days");
        assertThat(outcome(v, PredictionVerifier.MODEL_RESCORE)).isEqualTo(Outcome.PASS);
    }

    @Test
    void partlyPrunedWindowIsSkippedButMissingReadingsInsideRetentionFail() {
        var partly = verify(stored().window(READINGS.subList(6, 12))
                .retainedSince(READINGS.get(6).recordedAt()).build());
        assertThat(outcome(partly, PredictionVerifier.RAW_TELEMETRY)).isEqualTo(Outcome.SKIPPED);
        assertThat(partly.status()).isEqualTo(Status.VERIFIED);

        var deleted = verify(stored().window(List.of()).build());
        assertThat(outcome(deleted, PredictionVerifier.RAW_TELEMETRY)).isEqualTo(Outcome.FAIL);
        assertThat(deleted.status()).isEqualTo(Status.MISMATCH);
    }

    @Test
    void editedFeatureValueIsAMismatch() {
        List<RiskAssessment.Contribution> edited = new ArrayList<>(model.explain(features));
        var first = edited.getFirst();
        edited.set(0, new RiskAssessment.Contribution(first.feature(), first.label(), first.value() + 0.01, first.logOdds()));

        var v = verify(stored().contributions(edited).build());

        assertThat(v.status()).isEqualTo(Status.MISMATCH);
        assertThat(outcome(v, PredictionVerifier.FEATURE_DIGEST)).isEqualTo(Outcome.FAIL);
    }

    @Test
    void missingOrDuplicatedFeatureIsAMismatch() {
        var contributions = model.explain(features);
        var missing = verify(stored().contributions(contributions.subList(1, contributions.size())).build());
        List<RiskAssessment.Contribution> duplicated = new ArrayList<>(contributions);
        duplicated.set(1, duplicated.getFirst());
        var twice = verify(stored().contributions(duplicated).build());

        assertThat(outcome(missing, PredictionVerifier.FEATURE_DIGEST)).isEqualTo(Outcome.FAIL);
        assertThat(outcome(twice, PredictionVerifier.FEATURE_DIGEST)).isEqualTo(Outcome.FAIL);
        assertThat(outcome(twice, PredictionVerifier.MODEL_RESCORE)).isEqualTo(Outcome.SKIPPED);
    }

    @Test
    void editedChannelMeanNoLongerGivesTheRecordedFeatures() {
        var m = input.means();
        var editedMeans = new RiskAssessment.ChannelMeans(m.temperature(), m.vibration(), m.rpm() * 0.9, m.torque(), m.pressure());
        var edited = new RiskAssessment.InputReference(input.from(), input.to(), input.readings(), editedMeans,
                input.featuresSha256());

        var v = verify(stored().input(edited).build());

        assertThat(v.status()).isEqualTo(Status.MISMATCH);
        assertThat(outcome(v, PredictionVerifier.FEATURE_ENGINEERING)).isEqualTo(Outcome.FAIL);
        assertThat(outcome(v, PredictionVerifier.FEATURE_DIGEST)).isEqualTo(Outcome.PASS);
    }

    @Test
    void editedScoreFailsTheRescoreAndAWrongClassFailsTheClassCheck() {
        double p = model.probability(features);
        var nudged = verify(stored().probability(p + 1e-6).build());
        assertThat(outcome(nudged, PredictionVerifier.MODEL_RESCORE)).isEqualTo(Outcome.FAIL);

        RiskLevel wrong = RiskLevel.of(p) == RiskLevel.LOW ? RiskLevel.CRITICAL : RiskLevel.LOW;
        var reclassified = verify(stored().level(wrong).build());
        assertThat(outcome(reclassified, PredictionVerifier.RISK_CLASS)).isEqualTo(Outcome.FAIL);
        assertThat(reclassified.status()).isEqualTo(Status.MISMATCH);
    }

    @Test
    void changedOrLateRawReadingsAreDetected() {
        List<TelemetryReading> changed = new ArrayList<>(READINGS);
        var r = changed.get(5);
        changed.set(5, new TelemetryReading(r.machineId(), r.recordedAt(), r.temperature(), r.vibration(), r.rpm() + 60,
                r.torque(), r.pressure(), r.source()));
        List<TelemetryReading> late = new ArrayList<>(READINGS);
        late.add(6, r);

        assertThat(outcome(verify(stored().window(changed).build()), PredictionVerifier.RAW_TELEMETRY)).isEqualTo(Outcome.FAIL);
        assertThat(outcome(verify(stored().window(late).build()), PredictionVerifier.RAW_TELEMETRY)).isEqualTo(Outcome.FAIL);
    }

    @Test
    void predictionFromAnotherModelVersionIsNotRescoredButStillVerified() {
        var v = PredictionVerifier.verify(stored().build(),
                new PredictionVerifier.CurrentModel("1.0+w000000000000", model::probability));

        assertThat(v.status()).isEqualTo(Status.VERIFIED);
        assertThat(outcome(v, PredictionVerifier.MODEL_RESCORE)).isEqualTo(Outcome.SKIPPED);
        assertThat(detail(v, PredictionVerifier.MODEL_RESCORE)).contains("1.0+w000000000000");
    }

    @Test
    void predictionStoredBeforeV15IsUnverifiable() {
        var v = verify(stored().unrecorded().build());

        assertThat(v.status()).isEqualTo(Status.UNVERIFIABLE);
        assertThat(v.checks()).extracting(PredictionVerification.Check::outcome).containsExactly(Outcome.PASS, Outcome.SKIPPED);
    }

    @Test
    void unreadableInputRecordIsAMismatch() {
        var v = verify(stored().input(null).build());

        assertThat(v.status()).isEqualTo(Status.MISMATCH);
        assertThat(outcome(v, PredictionVerifier.INPUT_RECORD)).isEqualTo(Outcome.FAIL);
    }

    private static PredictionVerification verify(PredictionVerifier.Evidence e) {
        return PredictionVerifier.verify(e, new PredictionVerifier.CurrentModel(model.info().version(), model::probability));
    }

    private static Outcome outcome(PredictionVerification v, String check) {
        return v.checks().stream().filter(c -> c.name().equals(check)).findFirst().orElseThrow().outcome();
    }

    private static String detail(PredictionVerification v, String check) {
        return v.checks().stream().filter(c -> c.name().equals(check)).findFirst().orElseThrow().detail();
    }

    /** The prediction as RiskService and PredictionService record it; each setter changes one stored value. */
    private static Stored stored() {
        return new Stored();
    }

    private static final class Stored {
        double probability = model.probability(features);
        RiskLevel level = RiskLevel.of(probability);
        String modelVersion = model.info().version();
        boolean recorded = true;
        RiskAssessment.InputReference in = input;
        String reference = input.reference();
        List<RiskAssessment.Contribution> contributions = model.explain(features);
        List<TelemetryReading> window = READINGS;
        Instant retainedSince = KEPT;

        Stored probability(double p) {
            probability = p;
            return this;
        }

        Stored level(RiskLevel l) {
            level = l;
            return this;
        }

        Stored input(RiskAssessment.InputReference i) {
            in = i;
            return this;
        }

        Stored contributions(List<RiskAssessment.Contribution> c) {
            contributions = c;
            return this;
        }

        Stored window(List<TelemetryReading> w) {
            window = w;
            return this;
        }

        Stored retainedSince(Instant t) {
            retainedSince = t;
            return this;
        }

        /** A row migrated by V15 from before it: no input record. */
        Stored unrecorded() {
            recorded = false;
            in = null;
            modelVersion = "unrecorded";
            reference = "telemetry[unrecorded] n=12";
            window = List.of();
            return this;
        }

        PredictionVerifier.Evidence build() {
            return new PredictionVerifier.Evidence(7, "CNC-01", TYPE, TestReadings.T0.plus(Duration.ofMinutes(12)),
                    probability, level, READINGS.size(), FailureRiskModel.NAME, modelVersion, reference, recorded, in,
                    contributions, window, retainedSince, RETENTION);
        }
    }
}

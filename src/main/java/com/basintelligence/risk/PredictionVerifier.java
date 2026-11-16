package com.basintelligence.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.basintelligence.common.Text;
import com.basintelligence.machine.MachineType;
import com.basintelligence.risk.PredictionVerification.Check;
import com.basintelligence.risk.PredictionVerification.Outcome;
import com.basintelligence.risk.PredictionVerification.Status;
import com.basintelligence.telemetry.TelemetryReading;

/**
 * Checks a stored failure-risk prediction: that its record is internally consistent, that today's feature code
 * turns the recorded channel means into the recorded features, that the same model version gives the same score,
 * and, while the raw readings are still kept, that they reproduce the features. Plain class, no Spring or database.
 */
public final class PredictionVerifier {

    /** The same weights and features give the same score; this only absorbs floating-point noise. */
    static final double SCORE_TOLERANCE = 1e-12;

    static final String RISK_CLASS = "risk class";
    static final String INPUT_RECORD = "input record";
    static final String FEATURE_DIGEST = "feature digest";
    static final String FEATURE_ENGINEERING = "feature engineering";
    static final String MODEL_RESCORE = "model re-score";
    static final String RAW_TELEMETRY = "raw telemetry";

    private static final Pattern DIGEST = Pattern.compile("features=sha256:([0-9a-f]{64})");

    /**
     * What is stored for one prediction, and the raw readings still present in its input window.
     *
     * @param inputRecorded    false for rows written before migration V15 ({@code input_json} is NULL)
     * @param input            the parsed {@code input_json}; null when not recorded or not readable
     * @param contributions    the parsed {@code contributions_json}; null when not readable
     * @param readingsInWindow readings now stored between the window's first and last timestamp (at most
     *                         {@code input.readings() + 1})
     * @param retainedSince    raw readings older than this may have been deleted by retention
     */
    public record Evidence(
            long predictionId,
            String machineCode,
            MachineType machineType,
            Instant predictedAt,
            double probability,
            RiskLevel level,
            int readingsUsed,
            String modelName,
            String modelVersion,
            String inputReference,
            boolean inputRecorded,
            RiskAssessment.InputReference input,
            List<RiskAssessment.Contribution> contributions,
            List<TelemetryReading> readingsInWindow,
            Instant retainedSince,
            Duration retention) {
    }

    /** The model loaded in this process. */
    public record CurrentModel(String version, ToDoubleFunction<double[]> scorer) {
    }

    private PredictionVerifier() {
    }

    public static PredictionVerification verify(Evidence e, CurrentModel model) {
        List<Check> checks = new ArrayList<>();
        checks.add(riskClass(e));
        RiskAssessment.InputReference in = e.input();
        if (!e.inputRecorded()) {
            checks.add(skipped(INPUT_RECORD, "Stored before migration V15: the model version and the input were not "
                    + "recorded, so this prediction cannot be checked further."));
        } else if (in == null || in.means() == null || in.from() == null || in.to() == null || in.featuresSha256() == null) {
            checks.add(fail(INPUT_RECORD, "input_json cannot be read as an input record."));
        } else {
            Optional<double[]> features = RiskFeatures.fromContributions(e.contributions());
            checks.add(inputRecord(e, in));
            checks.add(featureDigest(e, in, features));
            checks.add(featureEngineering(e, in));
            checks.add(rescore(e, model, features));
            checks.add(rawTelemetry(e, in));
        }
        Status status = checks.stream().anyMatch(c -> c.outcome() == Outcome.FAIL) ? Status.MISMATCH
                : e.inputRecorded() ? Status.VERIFIED : Status.UNVERIFIABLE;
        return new PredictionVerification(e.predictionId(), e.machineCode(), e.predictedAt(), e.modelName(),
                e.modelVersion(), e.inputReference(), status, List.copyOf(checks));
    }

    private static Check riskClass(Evidence e) {
        RiskLevel expected = RiskLevel.of(e.probability());
        return expected == e.level()
                ? pass(RISK_CLASS, "predicted_class " + e.level() + " matches risk_score " + Text.num(e.probability(), 4)
                        + " (thresholds 0.25 / 0.50 / 0.75).")
                : fail(RISK_CLASS, "predicted_class is " + e.level() + ", but risk_score " + e.probability() + " gives "
                        + expected + ".");
    }

    private static Check inputRecord(Evidence e, RiskAssessment.InputReference in) {
        if (!in.reference().equals(e.inputReference()) || in.readings() != e.readingsUsed()) {
            return fail(INPUT_RECORD, "input_json (" + in.reference() + ") disagrees with input_reference ("
                    + e.inputReference() + ") or readings_used (" + e.readingsUsed() + ").");
        }
        return pass(INPUT_RECORD, in.readings() + " readings from " + in.from() + " to " + in.to()
                + "; input_json, input_reference and readings_used agree.");
    }

    private static Check featureDigest(Evidence e, RiskAssessment.InputReference in, Optional<double[]> features) {
        if (features.isEmpty()) {
            return fail(FEATURE_DIGEST, "contributions_json does not hold exactly one value for each model feature ("
                    + String.join(", ", RiskFeatures.NAMES) + ").");
        }
        String actual = RiskFeatures.sha256(features.get());
        String inReference = digestIn(e.inputReference());
        if (actual.equals(in.featuresSha256()) && actual.equals(inReference)) {
            return pass(FEATURE_DIGEST, "The " + RiskFeatures.NAMES.size() + " stored feature values hash to sha256:"
                    + abbreviate(actual) + ", as recorded.");
        }
        return fail(FEATURE_DIGEST, "The stored feature values hash to sha256:" + abbreviate(actual) + ", but input_json "
                + "records " + abbreviate(in.featuresSha256()) + " and input_reference "
                + (inReference == null ? "no digest" : abbreviate(inReference)) + ".");
    }

    private static Check featureEngineering(Evidence e, RiskAssessment.InputReference in) {
        String digest = RiskFeatures.sha256(RiskFeatures.fromMeans(e.machineType(), in.means()));
        return digest.equals(in.featuresSha256())
                ? pass(FEATURE_ENGINEERING, "Today's feature code and the " + e.machineType() + " nominal values turn "
                        + "the recorded channel means into the recorded features.")
                : fail(FEATURE_ENGINEERING, "The recorded channel means no longer give the recorded features: either the "
                        + "means were changed, or the feature code or the " + e.machineType()
                        + " nominal values changed after this prediction.");
    }

    private static Check rescore(Evidence e, CurrentModel model, Optional<double[]> features) {
        if (!model.version().equals(e.modelVersion())) {
            return skipped(MODEL_RESCORE, "Made by " + e.modelName() + " " + e.modelVersion() + "; the running model is "
                    + model.version() + ". Re-scoring needs that exact version (same recipe version, training seed "
                    + "and sample count).");
        }
        if (features.isEmpty()) {
            return skipped(MODEL_RESCORE, "No complete feature vector to re-score.");
        }
        double p = model.scorer().applyAsDouble(features.get());
        return Math.abs(p - e.probability()) <= SCORE_TOLERANCE
                ? pass(MODEL_RESCORE, "Re-scored with " + e.modelVersion() + ": " + Text.num(p, 6)
                        + ", the same as the stored risk_score.")
                : fail(MODEL_RESCORE, "Re-scoring with the same model version gives " + p + ", but the record says "
                        + e.probability() + ".");
    }

    private static Check rawTelemetry(Evidence e, RiskAssessment.InputReference in) {
        int n = in.readings();
        int found = e.readingsInWindow().size();
        String window = in.from() + " to " + in.to();
        boolean partlyPrunable = in.from().isBefore(e.retainedSince());
        if (found == n) {
            String digest = RiskFeatures.sha256(RiskFeatures.fromReadings(e.machineType(), e.readingsInWindow()));
            return digest.equals(in.featuresSha256())
                    ? pass(RAW_TELEMETRY, "The " + n + " raw readings are still stored and reproduce the recorded features.")
                    : fail(RAW_TELEMETRY, "The " + n + " readings now stored for " + window
                            + " give different features: they were changed after the prediction.");
        }
        if (found == 0 && in.to().isBefore(e.retainedSince())) {
            return skipped(RAW_TELEMETRY, "The " + n + " readings (" + window + ") were pruned: raw telemetry is kept "
                    + describe(e.retention()) + ". The recorded channel means and features remain.");
        }
        if (found < n && partlyPrunable) {
            return skipped(RAW_TELEMETRY, found + " of the " + n + " readings are left; retention ("
                    + describe(e.retention()) + ") has pruned the rest.");
        }
        return fail(RAW_TELEMETRY, found > n
                ? "The window " + window + " now holds more than the " + n + " readings the prediction used: readings "
                        + "arrived late or were added afterwards."
                : "Only " + found + " of the " + n + " readings for " + window + " are stored, although they are within "
                        + "the retention period.");
    }

    private static String digestIn(String inputReference) {
        if (inputReference == null) {
            return null;
        }
        Matcher m = DIGEST.matcher(inputReference);
        return m.find() ? m.group(1) : null;
    }

    private static String abbreviate(String digest) {
        return digest == null ? "nothing" : digest.length() <= 12 ? digest : digest.substring(0, 12) + "…";
    }

    private static String describe(Duration d) {
        return d.toDays() >= 1 ? d.toDays() + (d.toDays() == 1 ? " day" : " days") : d.toHours() + " hours";
    }

    private static Check pass(String name, String detail) {
        return new Check(name, Outcome.PASS, detail);
    }

    private static Check fail(String name, String detail) {
        return new Check(name, Outcome.FAIL, detail);
    }

    private static Check skipped(String name, String detail) {
        return new Check(name, Outcome.SKIPPED, detail);
    }
}

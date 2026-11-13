package com.basintelligence.risk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import smile.classification.LogisticRegression;

/**
 * L2-regularised logistic regression (Smile) over {@link RiskFeatures}. Trained in well under a second on
 * the CPU at startup, evaluated on a 20% hold-out split, and fully explainable: every prediction is
 * decomposed into per-feature contributions to the log-odds.
 */
public final class FailureRiskModel {

    private static final Logger log = LoggerFactory.getLogger(FailureRiskModel.class);

    private final LogisticRegression.Binomial model;
    private final double[] weights;
    private final RiskAssessment.ModelInfo info;

    private FailureRiskModel(LogisticRegression.Binomial model, RiskAssessment.ModelInfo info) {
        this.model = model;
        this.weights = model.coefficients(); // [w_0 .. w_{p-1}, intercept]
        this.info = info;
    }

    public static FailureRiskModel train(int samples, long seed) {
        SyntheticFailureData.Dataset data = SyntheticFailureData.generate(samples, seed);
        int split = (int) (samples * 0.8);
        double[][] trainX = Arrays.copyOfRange(data.x(), 0, split);
        int[] trainY = Arrays.copyOfRange(data.y(), 0, split);
        double[][] testX = Arrays.copyOfRange(data.x(), split, samples);
        int[] testY = Arrays.copyOfRange(data.y(), split, samples);

        var model = LogisticRegression.binomial(trainX, trainY, new LogisticRegression.Options(0.05, 1e-6, 1000));
        double[] scores = Arrays.stream(testX).mapToDouble(model::score).toArray();
        int correct = 0;
        for (int i = 0; i < testY.length; i++) {
            correct += ((scores[i] >= 0.5 ? 1 : 0) == testY[i]) ? 1 : 0;
        }
        double accuracy = (double) correct / testY.length;
        double auc = auc(scores, testY);
        log.info("Failure-risk model trained on {} samples: hold-out accuracy {}, AUC {}", split,
                String.format("%.3f", accuracy), String.format("%.3f", auc));
        return new FailureRiskModel(model, new RiskAssessment.ModelInfo("Logistic regression (Smile, L2)", split,
                accuracy, auc, "synthetic fault-mode simulation"));
    }

    public double probability(double[] features) {
        return model.score(features);
    }

    public List<RiskAssessment.Contribution> explain(double[] features) {
        List<RiskAssessment.Contribution> out = new ArrayList<>();
        for (int i = 0; i < features.length; i++) {
            out.add(new RiskAssessment.Contribution(RiskFeatures.NAMES.get(i), RiskFeatures.LABELS.get(i), features[i],
                    weights[i] * features[i]));
        }
        out.sort(Comparator.comparingDouble(RiskAssessment.Contribution::logOdds).reversed());
        return out;
    }

    public double[] weights() {
        return weights.clone();
    }

    public RiskAssessment.ModelInfo info() {
        return info;
    }

    /** Area under the ROC curve via the rank-sum (Mann-Whitney) formulation. */
    static double auc(double[] scores, int[] labels) {
        Integer[] order = new Integer[scores.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble(i -> scores[i]));
        double rankSum = 0;
        long positives = 0;
        for (int rank = 0; rank < order.length; rank++) {
            if (labels[order[rank]] == 1) {
                rankSum += rank + 1;
                positives++;
            }
        }
        long negatives = labels.length - positives;
        if (positives == 0 || negatives == 0) {
            return Double.NaN;
        }
        return (rankSum - positives * (positives + 1) / 2.0) / (positives * (double) negatives);
    }
}

package com.basintelligence.ml;

import smile.classification.DataFrameClassifier;
import smile.classification.LogisticRegression;
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.regression.DataFrameRegression;

/** Serializable wrappers that give every Smile model the same simple double[] interface. */
public final class Models {

    private Models() {
    }

    /** Standardises features with the training mean and standard deviation, then applies logistic regression. */
    public record StandardisedLogistic(LogisticRegression model, double[] mean, double[] std)
            implements ProbabilisticClassifier {

        @Override
        public double probability(double[] features) {
            double[] post = new double[2];
            model.predict(standardise(features, mean, std), post);
            return post[1];
        }
    }

    /** Tree models trained through Smile's DataFrame/Formula API (decision tree, random forest, boosting). */
    public record TreeClassifier(DataFrameClassifier model, String[] featureNames) implements ProbabilisticClassifier {

        @Override
        public double probability(double[] features) {
            double[] post = new double[2];
            model.predict(row(features, featureNames), post);
            return post[1];
        }
    }

    /** Any Smile DataFrame regressor (OLS, random forest, gradient boosting). */
    public record FrameRegressor(DataFrameRegression model, String[] featureNames) implements RegressionModel {

        @Override
        public double predict(double[] features) {
            return model.predict(row(features, featureNames));
        }
    }

    /** Multi-class tree model; returns class probabilities in label order. */
    public record FrameMultiClassifier(DataFrameClassifier model, String[] featureNames, String[] classNames)
            implements java.io.Serializable {

        public double[] probabilities(double[] features) {
            double[] post = new double[classNames.length];
            model.predict(row(features, featureNames), post);
            return post;
        }
    }

    /**
     * Isolation Forest trained on healthy vibration only. Scores above {@code threshold} (the 99th percentile of
     * healthy validation scores) are anomalies. Features are standardised with healthy-training statistics.
     */
    public record BearingAnomalyDetector(smile.anomaly.IsolationForest forest, double threshold, double[] mean,
            double[] std) implements java.io.Serializable {

        /** An alarm needs this many consecutive abnormal windows (about 256 ms at 12 kHz). */
        public static final int ALARM_WINDOWS = 3;

        public double score(double[] features) {
            return forest.score(standardise(features, mean, std));
        }

        public boolean anomalous(double[] features) {
            return score(features) > threshold;
        }
    }

    public static double[] standardise(double[] x, double[] mean, double[] std) {
        double[] z = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            z[i] = std[i] == 0 ? 0 : (x[i] - mean[i]) / std[i];
        }
        return z;
    }

    static Tuple row(double[] features, String[] names) {
        return DataFrame.of(new double[][] {features}, names).get(0);
    }
}

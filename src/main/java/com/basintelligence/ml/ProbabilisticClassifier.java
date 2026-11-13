package com.basintelligence.ml;

import java.io.Serializable;

/** A trained binary classifier exported by the ML lab. Feature order is defined by the model card. */
public interface ProbabilisticClassifier extends Serializable {

    /** Probability of the positive class (failure). */
    double probability(double[] features);
}

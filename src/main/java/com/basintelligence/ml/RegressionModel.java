package com.basintelligence.ml;

import java.io.Serializable;

/** A trained regressor exported by the ML lab. Feature order is defined by the model card. */
public interface RegressionModel extends Serializable {

    double predict(double[] features);
}

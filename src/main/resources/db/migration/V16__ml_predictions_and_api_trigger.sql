-- Predictions from the ML-lab endpoints (POST /api/ml/*). These models score data the caller sends, so device_id is
-- set only when the caller names a machine (?machine=CODE). One row per scored item and model.
--
-- risk_score is the model's 0-1 score for the bad outcome, and predicted_class follows from it and the threshold
-- in the model card. It is NULL when the model has no such score; nothing is derived to fill it.
--   process-failure-classifier       one row per batch item. Class failure / no_failure, risk_score = failure
--                                    probability (failure when >= the card threshold).
--   remaining-useful-life-regressor  one row per request. Class 0-30_cycles / 31-60_cycles / over_60_cycles. A
--                                    regression has no probability: risk_score is NULL, predicted_value = cycles.
--   bearing-anomaly-detector         one row per 1024-sample window. Class anomalous / normal, risk_score = the
--                                    Isolation Forest anomaly score (anomalous when > the card threshold).
--   bearing-fault-classifier         one row per window. Class = the most probable condition, the rule the lab
--                                    evaluated. risk_score is NULL: the lab validated no fault score or threshold
--                                    for this model. probabilities_json holds all four class probabilities. The
--                                    detector row of the same window says whether the window is abnormal.
-- model_version is the card version plus the first 12 hex of the artifact SHA-256, e.g. '1.0.0+355bad0b9685'.
-- input_reference holds the input itself for process failure; for RUL and vibration, which can be large, it holds
-- the extent and a SHA-256 of the values. request_id groups the rows of one call.
CREATE TABLE ml_predictions (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id          UUID NOT NULL,
    item_index          INTEGER NOT NULL,
    device_id           UUID REFERENCES devices (id) ON DELETE CASCADE,
    predicted_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    model_name          VARCHAR(80) NOT NULL,
    model_version       VARCHAR(80) NOT NULL,
    predicted_class     VARCHAR(40) NOT NULL,
    risk_score          DOUBLE PRECISION,
    predicted_value     DOUBLE PRECISION,
    probabilities_json  TEXT,
    input_reference     TEXT NOT NULL,
    requested_by        VARCHAR(80) NOT NULL,
    CONSTRAINT uq_ml_prediction_item UNIQUE (request_id, model_name, item_index),
    CONSTRAINT ck_ml_prediction_score CHECK (risk_score BETWEEN 0 AND 1)
);
CREATE INDEX idx_ml_prediction_device_time ON ml_predictions (device_id, predicted_at DESC);
CREATE INDEX idx_ml_prediction_model ON ml_predictions (model_name, model_version);

-- GET /api/machines/{code}/risk now stores its estimate, with trigger API.
ALTER TABLE model_predictions DROP CONSTRAINT ck_prediction_trigger;
ALTER TABLE model_predictions ADD CONSTRAINT ck_prediction_trigger
    CHECK (trigger_type IN ('SCHEDULED', 'ANOMALY', 'INVESTIGATION', 'AGENT', 'API'));

-- The governance view gains the ML-lab predictions and predicted_value, the output of a regression (NULL for every
-- other model). device_id is NULL for ML-lab rows not tied to a machine.
DROP VIEW prediction_governance;
CREATE VIEW prediction_governance AS
SELECT 'model_predictions'                AS source_table,
       id                                 AS prediction_id,
       device_id,
       predicted_at                       AS prediction_timestamp,
       model_name,
       model_version,
       risk_level                         AS predicted_class,
       probability                        AS risk_score,
       CAST(NULL AS DOUBLE PRECISION)     AS predicted_value,
       input_reference,
       trigger_type
FROM model_predictions
UNION ALL
SELECT 'inspections',
       id,
       device_id,
       inspected_at,
       'defect-detector',
       model_version,
       verdict,
       defect_probability,
       CAST(NULL AS DOUBLE PRECISION),
       CASE WHEN image_sha256 IS NULL THEN 'image:' || image_path
            ELSE 'image:' || image_path || ' sha256:' || image_sha256 END,
       'INSPECTION'
FROM inspections
UNION ALL
SELECT 'ml_predictions',
       id,
       device_id,
       predicted_at,
       model_name,
       model_version,
       predicted_class,
       risk_score,
       predicted_value,
       input_reference,
       'API'
FROM ml_predictions;

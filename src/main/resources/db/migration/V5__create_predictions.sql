-- Failure-risk predictions. An anomaly event can trigger a prediction (anomaly_events -> model_predictions).
CREATE TABLE model_predictions (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id           UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    anomaly_event_id    BIGINT REFERENCES anomaly_events (id) ON DELETE SET NULL,
    predicted_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    trigger_type        VARCHAR(20) NOT NULL,
    probability         DOUBLE PRECISION NOT NULL,
    risk_level          VARCHAR(20) NOT NULL,
    readings_used       INTEGER NOT NULL,
    model_name          VARCHAR(80) NOT NULL,
    model_auc           DOUBLE PRECISION,
    contributions_json  TEXT NOT NULL,
    CONSTRAINT ck_prediction_probability CHECK (probability BETWEEN 0 AND 1),
    CONSTRAINT ck_prediction_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_prediction_trigger CHECK (trigger_type IN ('SCHEDULED', 'ANOMALY', 'INVESTIGATION'))
);
CREATE INDEX idx_prediction_device_time ON model_predictions (device_id, predicted_at DESC);
CREATE INDEX idx_prediction_anomaly ON model_predictions (anomaly_event_id);

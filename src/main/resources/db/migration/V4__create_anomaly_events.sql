CREATE TABLE anomaly_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id       UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    detected_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    metric          VARCHAR(20) NOT NULL,
    detector        VARCHAR(20) NOT NULL,
    severity        VARCHAR(20) NOT NULL,
    observed_value  DOUBLE PRECISION NOT NULL,
    expected_value  DOUBLE PRECISION,
    message         VARCHAR(500) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_anomaly_detector CHECK (detector IN ('ENVELOPE', 'ZSCORE')),
    CONSTRAINT ck_anomaly_severity CHECK (severity IN ('WARNING', 'CRITICAL'))
);
CREATE INDEX idx_anomaly_device_time ON anomaly_events (device_id, detected_at DESC);

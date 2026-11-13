-- Raw readings (short retention) plus 5-minute rollups (long retention) for dashboard charts.
CREATE TABLE telemetry (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id       UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    recorded_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    temperature     DOUBLE PRECISION NOT NULL,
    vibration       DOUBLE PRECISION NOT NULL,
    rpm             DOUBLE PRECISION NOT NULL,
    torque          DOUBLE PRECISION NOT NULL,
    pressure        DOUBLE PRECISION NOT NULL,
    source          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    -- Same physical plausibility bounds as the edge API validation.
    CONSTRAINT ck_telemetry_ranges CHECK (
        temperature BETWEEN -40 AND 400 AND vibration BETWEEN 0 AND 100 AND rpm BETWEEN 0 AND 30000
        AND torque BETWEEN -2000 AND 2000 AND pressure BETWEEN 0 AND 100),
    CONSTRAINT ck_telemetry_source CHECK (source IN ('EDGE', 'SIMULATOR'))
);
CREATE INDEX idx_telemetry_device_time ON telemetry (device_id, recorded_at DESC);

CREATE TABLE telemetry_rollup_5m (
    device_id       UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    bucket_start    TIMESTAMP WITH TIME ZONE NOT NULL,
    sample_count    INTEGER NOT NULL,
    temperature_avg DOUBLE PRECISION NOT NULL,
    temperature_max DOUBLE PRECISION NOT NULL,
    vibration_avg   DOUBLE PRECISION NOT NULL,
    vibration_max   DOUBLE PRECISION NOT NULL,
    rpm_avg         DOUBLE PRECISION NOT NULL,
    rpm_min         DOUBLE PRECISION NOT NULL,
    torque_avg      DOUBLE PRECISION NOT NULL,
    torque_max      DOUBLE PRECISION NOT NULL,
    pressure_avg    DOUBLE PRECISION NOT NULL,
    pressure_min    DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (device_id, bucket_start)
);

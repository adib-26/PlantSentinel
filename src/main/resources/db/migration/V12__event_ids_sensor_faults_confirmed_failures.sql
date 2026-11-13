-- Four different kinds of event, kept apart on purpose:
--   sensor_faults     a reading that cannot be trusted (missing, impossible, stuck, bad timestamp). Not machine behaviour.
--   anomaly_events    the machine behaves abnormally according to the detectors (existing table).
--   model_predictions a model's estimate of failure risk (existing table). A prediction is not a failure.
--   failure_events    a failure confirmed by a person who inspected the machine. Only humans can record these.

-- Idempotent edge ingestion: a gateway may resend a reading after a lost response. NULL event ids are allowed
-- (NULLs are distinct in unique indexes on both PostgreSQL and H2).
ALTER TABLE telemetry ADD COLUMN event_id VARCHAR(100);
CREATE UNIQUE INDEX uq_telemetry_device_event ON telemetry (device_id, event_id);

CREATE TABLE sensor_faults (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id       UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    detected_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    reading_time    TIMESTAMP WITH TIME ZONE,
    gateway_id      VARCHAR(80),
    event_id        VARCHAR(100),
    fault_type      VARCHAR(30) NOT NULL,
    channel         VARCHAR(20) NOT NULL,
    raw_value       VARCHAR(40),
    message         VARCHAR(300) NOT NULL,
    CONSTRAINT ck_sensor_fault_type CHECK (fault_type IN ('MISSING_VALUE', 'IMPOSSIBLE_VALUE', 'STUCK_VALUE', 'INVALID_TIMESTAMP'))
);
CREATE INDEX idx_sensor_faults_device_time ON sensor_faults (device_id, detected_at DESC);

CREATE TABLE failure_events (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id               UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    occurred_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    failure_mode            VARCHAR(30) NOT NULL,
    description             VARCHAR(2000) NOT NULL,
    confirmed_by            VARCHAR(80) NOT NULL,
    confirmed_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    maintenance_record_id   BIGINT REFERENCES maintenance_records (id) ON DELETE SET NULL,
    investigation_id        BIGINT REFERENCES investigations (id) ON DELETE SET NULL,
    -- The last prediction made before the failure, so predictions can later be checked against reality.
    prior_prediction_id     BIGINT REFERENCES model_predictions (id) ON DELETE SET NULL,
    CONSTRAINT ck_failure_mode CHECK (failure_mode IN ('BEARING', 'COOLING', 'OVERLOAD', 'LEAK', 'ELECTRICAL',
                                                       'STRUCTURAL_CRACK', 'OTHER')),
    -- Automated actors can predict and propose, never confirm.
    CONSTRAINT ck_failure_confirmed_by_person CHECK (confirmed_by NOT IN ('kiro-agent', 'bas-diagnostics', 'system'))
);
CREATE INDEX idx_failure_events_device_time ON failure_events (device_id, occurred_at DESC);

-- Machines that have ever received data from a real BAS edge node. The demo simulator never writes for them, so
-- an edge outage shows up as missing data (STALE / OFFLINE) instead of being hidden by simulated readings.
ALTER TABLE devices ADD COLUMN edge_managed BOOLEAN NOT NULL DEFAULT FALSE;

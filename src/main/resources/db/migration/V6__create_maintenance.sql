-- Maintenance history. Approved recommendations create PLANNED records (FK added in V8).
CREATE TABLE maintenance_records (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id           UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    recommendation_id   BIGINT,
    action              VARCHAR(1000) NOT NULL,
    priority            VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    planned_by          VARCHAR(80) NOT NULL,
    planned_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_by        VARCHAR(80),
    completed_at        TIMESTAMP WITH TIME ZONE,
    notes               VARCHAR(2000),
    CONSTRAINT ck_maintenance_status CHECK (status IN ('PLANNED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_maintenance_completion CHECK (status <> 'COMPLETED' OR (completed_by IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX idx_maintenance_device_time ON maintenance_records (device_id, planned_at DESC);

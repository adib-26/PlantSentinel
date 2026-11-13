-- Machine categories. Rows are kept in sync with the MachineType enum at startup.
CREATE TABLE device_types (
    code            VARCHAR(30) PRIMARY KEY,
    display_name    VARCHAR(120) NOT NULL
);

-- Operating envelope per category and metric. Low limits are NULL when only a high limit applies.
CREATE TABLE device_type_limits (
    device_type     VARCHAR(30) NOT NULL REFERENCES device_types (code) ON DELETE CASCADE,
    metric          VARCHAR(20) NOT NULL,
    unit            VARCHAR(20) NOT NULL,
    nominal         DOUBLE PRECISION NOT NULL,
    warn_low        DOUBLE PRECISION,
    crit_low        DOUBLE PRECISION,
    warn_high       DOUBLE PRECISION NOT NULL,
    crit_high       DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (device_type, metric),
    CONSTRAINT ck_limits_order CHECK (warn_high < crit_high)
);

CREATE TABLE devices (
    id              UUID PRIMARY KEY,
    factory_id      UUID NOT NULL REFERENCES factories (id),
    code            VARCHAR(40)  NOT NULL UNIQUE,
    name            VARCHAR(120) NOT NULL,
    device_type     VARCHAR(30)  NOT NULL REFERENCES device_types (code),
    location        VARCHAR(120),
    health_status   VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    last_seen_at    TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_devices_health CHECK (health_status IN ('UNKNOWN', 'NORMAL', 'WARNING', 'CRITICAL'))
);
CREATE INDEX idx_devices_factory ON devices (factory_id);

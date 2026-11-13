CREATE TABLE inspections (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id           UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    inspected_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    component           VARCHAR(120) NOT NULL,
    original_filename   VARCHAR(255),
    image_path          VARCHAR(500) NOT NULL,
    annotated_path      VARCHAR(500),
    defect_probability  DOUBLE PRECISION NOT NULL,
    verdict             VARCHAR(20) NOT NULL,
    defect_regions      INTEGER NOT NULL,
    model_version       VARCHAR(80) NOT NULL,
    inference_ms        BIGINT NOT NULL,
    inspected_by        VARCHAR(80) NOT NULL,
    CONSTRAINT ck_inspection_verdict CHECK (verdict IN ('PASS', 'DEFECT'))
);
CREATE INDEX idx_inspection_device_time ON inspections (device_id, inspected_at DESC);

CREATE TABLE investigations (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id       UUID NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    prediction_id   BIGINT REFERENCES model_predictions (id) ON DELETE SET NULL,
    question        VARCHAR(1000),
    status          VARCHAR(20) NOT NULL,
    risk_score      DOUBLE PRECISION,
    risk_level      VARCHAR(20),
    diagnosis       VARCHAR(500),
    explanation     TEXT,
    requested_by    VARCHAR(80) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    closed_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_investigation_status CHECK (status IN ('OPEN', 'REVIEWED', 'CLOSED'))
);
CREATE INDEX idx_investigation_device ON investigations (device_id, created_at DESC);

CREATE TABLE investigation_evidence (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    investigation_id    BIGINT NOT NULL REFERENCES investigations (id) ON DELETE CASCADE,
    ref_label           VARCHAR(10) NOT NULL,
    kind                VARCHAR(20) NOT NULL,
    title               VARCHAR(255) NOT NULL,
    detail              TEXT NOT NULL,
    source              VARCHAR(255),
    CONSTRAINT uq_evidence_label UNIQUE (investigation_id, ref_label)
);

CREATE TABLE recommendations (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    investigation_id    BIGINT NOT NULL REFERENCES investigations (id) ON DELETE CASCADE,
    action              VARCHAR(1000) NOT NULL,
    rationale           TEXT NOT NULL,
    priority            VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    proposed_by         VARCHAR(80) NOT NULL,
    proposed_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_recommendation_status CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_recommendation_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT'))
);
CREATE INDEX idx_recommendation_investigation ON recommendations (investigation_id);

-- Human approval workflow. One decision per recommendation; the database enforces the four-eyes rule.
CREATE TABLE approvals (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    recommendation_id   BIGINT NOT NULL UNIQUE REFERENCES recommendations (id) ON DELETE CASCADE,
    decision            VARCHAR(20) NOT NULL,
    proposed_by         VARCHAR(80) NOT NULL,
    decided_by          VARCHAR(80) NOT NULL,
    decided_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    comment             VARCHAR(1000),
    CONSTRAINT ck_approval_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_approval_four_eyes CHECK (decided_by <> proposed_by)
);

CREATE TABLE incident_reports (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    investigation_id    BIGINT NOT NULL UNIQUE REFERENCES investigations (id) ON DELETE CASCADE,
    title               VARCHAR(255) NOT NULL,
    body_markdown       TEXT NOT NULL,
    generated_by        VARCHAR(80) NOT NULL,
    generated_at        TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE maintenance_records ADD CONSTRAINT fk_maintenance_recommendation
    FOREIGN KEY (recommendation_id) REFERENCES recommendations (id) ON DELETE SET NULL;

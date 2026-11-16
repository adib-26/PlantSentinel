-- Model governance: every stored prediction records which exact model produced it and from which input.
--   model_name       stable identifier ('failure-risk'), previously the algorithm description
--   model_version    training-recipe version + digest of the fitted coefficients, e.g. '1.0+w3f9a0c12b7de'
--   input_reference  telemetry window, reading count and SHA-256 of the feature vector
--   input_json       the input itself (window, reading count, mean of each channel, feature digest), so a
--                    prediction stays checkable after raw telemetry is pruned; the feature values are in
--                    contributions_json. About 300 bytes per row.
ALTER TABLE model_predictions ADD COLUMN model_version VARCHAR(80);
ALTER TABLE model_predictions ADD COLUMN input_reference TEXT;
ALTER TABLE model_predictions ADD COLUMN input_json TEXT;

-- Rows written before this migration: only one model ever existed; its version and input were not recorded,
-- so input_json stays NULL and verification reports them as UNVERIFIABLE.
UPDATE model_predictions SET model_name = 'failure-risk' WHERE model_name = 'Logistic regression (Smile, L2)';
UPDATE model_predictions SET model_version = 'unrecorded' WHERE model_version IS NULL;
UPDATE model_predictions SET input_reference = 'telemetry[unrecorded] n=' || readings_used WHERE input_reference IS NULL;

ALTER TABLE model_predictions ALTER COLUMN model_version SET NOT NULL;
ALTER TABLE model_predictions ALTER COLUMN input_reference SET NOT NULL;

CREATE INDEX idx_prediction_model ON model_predictions (model_name, model_version);

-- Vision inspections already store model_version, verdict, probability and the image file name; the digest of the
-- uploaded bytes makes the input reference checkable. NULL for inspections stored before this migration.
ALTER TABLE inspections ADD COLUMN image_sha256 VARCHAR(64);

-- The governance record under the agreed field names, for both models that score registered machines.
-- (source_table, prediction_id) is the key. predicted_class is the risk level derived from risk_score for
-- failure-risk, and the PASS / DEFECT verdict for defect-detector.
CREATE VIEW prediction_governance AS
SELECT 'model_predictions' AS source_table,
       id                  AS prediction_id,
       device_id,
       predicted_at        AS prediction_timestamp,
       model_name,
       model_version,
       risk_level          AS predicted_class,
       probability         AS risk_score,
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
       CASE WHEN image_sha256 IS NULL THEN 'image:' || image_path
            ELSE 'image:' || image_path || ' sha256:' || image_sha256 END,
       'INSPECTION'
FROM inspections;

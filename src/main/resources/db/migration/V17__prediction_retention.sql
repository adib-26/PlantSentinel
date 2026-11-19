-- Retention for the prediction tables (bas.predictions.*). The hourly jobs delete rows older than the retention
-- period and, past a table's row limit, the oldest rows; failure-risk predictions that an investigation or a
-- confirmed failure refers to are kept. These indexes let the jobs find old rows without a full scan and check those
-- references cheaply. PostgreSQL does not index foreign-key columns by itself.
CREATE INDEX idx_prediction_time ON model_predictions (predicted_at);
CREATE INDEX idx_ml_prediction_time ON ml_predictions (predicted_at);
CREATE INDEX idx_investigation_prediction ON investigations (prediction_id);
CREATE INDEX idx_failure_event_prior_prediction ON failure_events (prior_prediction_id);

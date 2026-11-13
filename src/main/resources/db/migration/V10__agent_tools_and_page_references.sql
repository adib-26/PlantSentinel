-- Predictions requested by the agent (predictFailureRisk) are stored with their own trigger type.
ALTER TABLE model_predictions DROP CONSTRAINT ck_prediction_trigger;
ALTER TABLE model_predictions ADD CONSTRAINT ck_prediction_trigger
    CHECK (trigger_type IN ('SCHEDULED', 'ANOMALY', 'INVESTIGATION', 'AGENT'));

-- Tool calls refused by the agent policy are audited as DENIED.
ALTER TABLE agent_tool_audit DROP CONSTRAINT ck_tool_status;
ALTER TABLE agent_tool_audit ADD CONSTRAINT ck_tool_status CHECK (status IN ('RUNNING', 'SUCCESS', 'ERROR', 'DENIED'));

-- Page references for PDF manuals so answers can cite "document › section, p. 4". NULL for formats without pages.
ALTER TABLE document_chunks ADD COLUMN page_start INTEGER;
ALTER TABLE document_chunks ADD COLUMN page_end INTEGER;
ALTER TABLE document_chunks ADD CONSTRAINT ck_chunk_pages
    CHECK (page_start IS NULL OR (page_start >= 1 AND page_end >= page_start));

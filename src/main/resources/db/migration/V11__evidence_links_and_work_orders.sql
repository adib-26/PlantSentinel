-- Make AI evidence traceable in the UI.
-- Manual evidence points at the exact document section it came from.
ALTER TABLE investigation_evidence ADD COLUMN document_chunk_id BIGINT;
ALTER TABLE investigation_evidence ADD CONSTRAINT fk_evidence_chunk
    FOREIGN KEY (document_chunk_id) REFERENCES document_chunks (id) ON DELETE SET NULL;
CREATE INDEX idx_evidence_chunk ON investigation_evidence (document_chunk_id);

-- Investigations started by the agent point at the tool call that created them (for the tool execution trace).
ALTER TABLE investigations ADD COLUMN created_via_tool_call BIGINT;
ALTER TABLE investigations ADD CONSTRAINT fk_investigation_tool_call
    FOREIGN KEY (created_via_tool_call) REFERENCES agent_tool_audit (id) ON DELETE SET NULL;

-- Work orders: technician assignment.
ALTER TABLE maintenance_records ADD COLUMN assigned_to VARCHAR(80);
ALTER TABLE maintenance_records ADD COLUMN assigned_by VARCHAR(80);
ALTER TABLE maintenance_records ADD COLUMN assigned_at TIMESTAMP WITH TIME ZONE;

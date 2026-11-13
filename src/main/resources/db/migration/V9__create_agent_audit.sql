-- Kiro talks to BAS Intelligence over MCP. The conversation itself lives in Kiro, so instead of chat
-- transcripts the app records each MCP session and every tool call the agent makes.
CREATE TABLE agent_sessions (
    id              VARCHAR(100) PRIMARY KEY,
    principal       VARCHAR(80)  NOT NULL,
    client_name     VARCHAR(120),
    client_version  VARCHAR(60),
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_seen_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    tool_calls      INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE agent_tool_audit (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id      VARCHAR(100) REFERENCES agent_sessions (id) ON DELETE SET NULL,
    principal       VARCHAR(80)  NOT NULL,
    tool_name       VARCHAR(80)  NOT NULL,
    arguments_json  VARCHAR(2000),
    status          VARCHAR(20)  NOT NULL,
    error_message   VARCHAR(1000),
    duration_ms     BIGINT,
    called_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_tool_status CHECK (status IN ('RUNNING', 'SUCCESS', 'ERROR'))
);
CREATE INDEX idx_tool_audit_time ON agent_tool_audit (called_at DESC);
CREATE INDEX idx_tool_audit_session ON agent_tool_audit (session_id);

-- approvals -> recommendations -> agent_tool_audit: which agent tool call proposed a recommendation.
ALTER TABLE recommendations ADD COLUMN proposed_via_tool_call BIGINT;
ALTER TABLE recommendations ADD CONSTRAINT fk_recommendation_tool_call
    FOREIGN KEY (proposed_via_tool_call) REFERENCES agent_tool_audit (id) ON DELETE SET NULL;

-- General audit trail of human and system actions.
CREATE TABLE audit_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    actor           VARCHAR(80)  NOT NULL,
    action          VARCHAR(60)  NOT NULL,
    target          VARCHAR(120) NOT NULL,
    detail          VARCHAR(1000)
);
CREATE INDEX idx_audit_time ON audit_log (occurred_at DESC);

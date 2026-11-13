package com.basintelligence.agent;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.common.Text;

/**
 * Records Kiro's MCP sessions ({@code agent_sessions}) and every tool call ({@code agent_tool_audit}). Writes use
 * their own transactions so the audit survives even when the tool itself fails and rolls back.
 */
@Service
public class AgentAuditService {

    public enum Outcome {
        SUCCESS, ERROR, DENIED
    }

    public record ToolCall(long id, String sessionId, String principal, String toolName, String argumentsJson,
            String status, String errorMessage, Long durationMs, Instant calledAt) {
    }

    public record AgentSession(String id, String principal, String clientName, String clientVersion, Instant startedAt,
            Instant lastSeenAt, int toolCalls) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public AgentAuditService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long start(String sessionId, String clientName, String clientVersion, String principal, String tool,
            String argumentsJson) {
        Instant now = clock.instant();
        if (sessionId != null) {
            int updated = jdbc.sql("""
                    UPDATE agent_sessions SET last_seen_at = :now, tool_calls = tool_calls + 1 WHERE id = :id""")
                    .param("now", utc(now)).param("id", sessionId).update();
            if (updated == 0) {
                jdbc.sql("""
                        INSERT INTO agent_sessions (id, principal, client_name, client_version, started_at, last_seen_at, tool_calls)
                        VALUES (:id, :principal, :client, :version, :now, :now, 1)""")
                        .param("id", Text.truncate(sessionId, 100)).param("principal", principal)
                        .param("client", Text.truncate(clientName, 120)).param("version", Text.truncate(clientVersion, 60))
                        .param("now", utc(now)).update();
            }
        }
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO agent_tool_audit (session_id, principal, tool_name, arguments_json, status, called_at)
                VALUES (:session, :principal, :tool, :args, 'RUNNING', :at)""")
                .param("session", sessionId).param("principal", principal).param("tool", tool)
                .param("args", Text.truncate(argumentsJson, 2000)).param("at", utc(now))
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(long callId, Outcome outcome, String error, long durationMs) {
        jdbc.sql("UPDATE agent_tool_audit SET status = :status, error_message = :error, duration_ms = :ms WHERE id = :id")
                .param("status", outcome.name()).param("error", Text.truncate(error, 1000))
                .param("ms", durationMs).param("id", callId).update();
    }

    public List<ToolCall> recentCalls(int limit) {
        return jdbc.sql("SELECT * FROM agent_tool_audit ORDER BY id DESC LIMIT :l").param("l", limit)
                .query((rs, n) -> new ToolCall(rs.getLong("id"), rs.getString("session_id"), rs.getString("principal"),
                        rs.getString("tool_name"), rs.getString("arguments_json"), rs.getString("status"),
                        rs.getString("error_message"), rs.getObject("duration_ms", Long.class), instant(rs, "called_at")))
                .list();
    }

    /**
     * The tool calls that led to an agent investigation: every call in the same MCP session after the previous
     * report and up to the report call, plus the drafts later created from it.
     */
    public List<ToolCall> traceFor(long reportCallId, List<Long> draftCallIds) {
        String session = jdbc.sql("SELECT session_id FROM agent_tool_audit WHERE id = :id").param("id", reportCallId)
                .query(String.class).optional().orElse(null);
        if (session == null) {
            return jdbc.sql("SELECT * FROM agent_tool_audit WHERE id = :id").param("id", reportCallId).query(MAPPER).list();
        }
        Long previous = jdbc.sql("""
                SELECT MAX(id) FROM agent_tool_audit WHERE session_id = :s AND id < :id AND tool_name = 'generateMaintenanceReport'""")
                .param("s", session).param("id", reportCallId).query((rs, n) -> rs.getObject(1, Long.class)).list().get(0);
        List<ToolCall> calls = new java.util.ArrayList<>(jdbc.sql(
                "SELECT * FROM agent_tool_audit WHERE session_id = :s AND id > :from AND id <= :to ORDER BY id")
                .param("s", session).param("from", previous == null ? 0L : previous).param("to", reportCallId)
                .query(MAPPER).list());
        for (Long draft : draftCallIds) {
            jdbc.sql("SELECT * FROM agent_tool_audit WHERE id = :id").param("id", draft).query(MAPPER).optional()
                    .filter(c -> calls.stream().noneMatch(x -> x.id() == c.id())).ifPresent(calls::add);
        }
        return calls;
    }

    private static final org.springframework.jdbc.core.RowMapper<ToolCall> MAPPER = (rs, n) -> new ToolCall(rs.getLong("id"),
            rs.getString("session_id"), rs.getString("principal"), rs.getString("tool_name"), rs.getString("arguments_json"),
            rs.getString("status"), rs.getString("error_message"), rs.getObject("duration_ms", Long.class),
            instant(rs, "called_at"));

    public List<AgentSession> recentSessions(int limit) {
        return jdbc.sql("SELECT * FROM agent_sessions ORDER BY last_seen_at DESC LIMIT :l").param("l", limit)
                .query((rs, n) -> new AgentSession(rs.getString("id"), rs.getString("principal"), rs.getString("client_name"),
                        rs.getString("client_version"), instant(rs, "started_at"), instant(rs, "last_seen_at"),
                        rs.getInt("tool_calls")))
                .list();
    }
}

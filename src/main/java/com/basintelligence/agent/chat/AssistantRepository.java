package com.basintelligence.agent.chat;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.basintelligence.common.Text;

/** Conversations with Kiro and their messages ({@code assistant_conversations}, {@code assistant_messages}). */
@Repository
public class AssistantRepository {

    public enum Role {
        USER, KIRO
    }

    public enum Status {
        SENT, RUNNING, DONE, FAILED, TIMED_OUT, CANCELLED
    }

    public record Conversation(long id, String username, String title, String kiroSessionId, Instant createdAt,
            Instant updatedAt) {
    }

    public record Message(long id, long conversationId, Role role, String content, Status status, String errorMessage,
            Double credits, Instant createdAt, Instant finishedAt) {
    }

    /** A report (investigation) that a Kiro run created. */
    public record ReportLink(long investigationId, String machineCode) {
    }

    private static final RowMapper<Conversation> CONVERSATION = (rs, n) -> new Conversation(rs.getLong("id"),
            rs.getString("username"), rs.getString("title"), rs.getString("kiro_session_id"), instant(rs, "created_at"),
            instant(rs, "updated_at"));
    private static final RowMapper<Message> MESSAGE = (rs, n) -> new Message(rs.getLong("id"),
            rs.getLong("conversation_id"), Role.valueOf(rs.getString("role")), rs.getString("content"),
            Status.valueOf(rs.getString("status")), rs.getString("error_message"), rs.getObject("credits", Double.class),
            instant(rs, "created_at"), instant(rs, "finished_at"));

    private final JdbcClient jdbc;

    public AssistantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insertConversation(String username, String title, Instant at) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO assistant_conversations (username, title, created_at, updated_at)
                VALUES (:u, :title, :at, :at)""")
                .param("u", username).param("title", Text.truncate(title, 200)).param("at", utc(at))
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public Optional<Conversation> findConversation(long id) {
        return jdbc.sql("SELECT * FROM assistant_conversations WHERE id = :id").param("id", id).query(CONVERSATION).optional();
    }

    public List<Conversation> conversations(String username, int limit) {
        return jdbc.sql("SELECT * FROM assistant_conversations WHERE username = :u ORDER BY updated_at DESC, id DESC LIMIT :l")
                .param("u", username).param("l", limit).query(CONVERSATION).list();
    }

    public void updateConversation(long id, String kiroSessionId, Instant at) {
        jdbc.sql("""
                UPDATE assistant_conversations SET updated_at = :at,
                    kiro_session_id = COALESCE(:session, kiro_session_id) WHERE id = :id""")
                .param("at", utc(at)).param("session", kiroSessionId).param("id", id).update();
    }

    public long insertMessage(long conversationId, Role role, String content, Status status, Instant at) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO assistant_messages (conversation_id, role, content, status, created_at)
                VALUES (:c, :role, :content, :status, :at)""")
                .param("c", conversationId).param("role", role.name()).param("content", content)
                .param("status", status.name()).param("at", utc(at))
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    public void finishMessage(long id, Status status, String content, String error, Double credits, Instant at) {
        jdbc.sql("""
                UPDATE assistant_messages SET status = :status, content = :content, error_message = :error,
                    credits = :credits, finished_at = :at WHERE id = :id AND status = 'RUNNING'""")
                .param("status", status.name()).param("content", content).param("error", Text.truncate(error, 500))
                .param("credits", credits).param("at", utc(at)).param("id", id).update();
    }

    public Optional<Message> findMessage(long id) {
        return jdbc.sql("SELECT * FROM assistant_messages WHERE id = :id").param("id", id).query(MESSAGE).optional();
    }

    /** The latest {@code limit} messages, oldest first. */
    public List<Message> messages(long conversationId, int limit) {
        return jdbc.sql("""
                SELECT * FROM (SELECT * FROM assistant_messages WHERE conversation_id = :c ORDER BY id DESC LIMIT :l) m
                ORDER BY id""").param("c", conversationId).param("l", limit).query(MESSAGE).list();
    }

    public boolean hasRunningMessage(long conversationId) {
        return jdbc.sql("SELECT COUNT(*) FROM assistant_messages WHERE conversation_id = :c AND status = 'RUNNING'")
                .param("c", conversationId).query(Long.class).single() > 0;
    }

    /** After a restart no run is alive; their messages end as FAILED. Returns how many. */
    public int failRunning(String reason, Instant at) {
        return jdbc.sql("""
                UPDATE assistant_messages SET status = 'FAILED', error_message = :reason, finished_at = :at
                WHERE status = 'RUNNING'""").param("reason", reason).param("at", utc(at)).update();
    }

    /** Reports created by the tool calls of one Kiro message. */
    public List<ReportLink> reports(long messageId) {
        return jdbc.sql("""
                SELECT i.id, d.code FROM investigations i JOIN devices d ON d.id = i.device_id
                WHERE i.created_via_tool_call IN (SELECT a.id FROM agent_tool_audit a WHERE a.assistant_message_id = :m)
                ORDER BY i.id""").param("m", messageId)
                .query((rs, n) -> new ReportLink(rs.getLong(1), rs.getString(2))).list();
    }
}

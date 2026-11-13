package com.basintelligence.common;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Append-only record of security-relevant and workflow actions (who did what, when). */
@Service
public class AuditService {

    public record AuditEntry(Instant occurredAt, String actor, String action, String target, String detail) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(String actor, String action, String target, String detail) {
        jdbc.sql("""
                INSERT INTO audit_log (occurred_at, actor, action, target, detail)
                VALUES (:at, :actor, :action, :target, :detail)""")
                .param("at", OffsetDateTime.now(clock))
                .param("actor", Text.truncate(actor, 80))
                .param("action", action)
                .param("target", Text.truncate(target, 120))
                .param("detail", Text.truncate(detail, 1000))
                .update();
    }

    /**
     * Records at most one entry per {@code key} and {@code interval}, for events an outsider can trigger at will
     * (failed logins, bad API keys, denied requests, rate limiting), so a flood cannot fill the table. Suppressed
     * repeats are counted and reported in the next entry for the same key.
     *
     * @return true if an entry was written
     */
    public boolean recordThrottled(String key, Duration interval, String actor, String action, String target, String detail) {
        Instant now = clock.instant();
        boolean[] write = {false};
        int[] suppressed = {0};
        throttle.compute(key, (k, w) -> {
            if (w == null || !w.until().isAfter(now)) {
                write[0] = true;
                suppressed[0] = w == null ? 0 : w.suppressed();
                return new Window(now.plus(interval), 0);
            }
            return new Window(w.until(), w.suppressed() + 1);
        });
        if (!write[0]) {
            return false;
        }
        if (throttle.size() > MAX_THROTTLE_KEYS) {
            throttle.entrySet().removeIf(e -> !e.getValue().until().isAfter(now));
        }
        String suffix = suppressed[0] > 0 ? " (+" + suppressed[0] + " similar suppressed)" : "";
        record(actor, action, target, (detail == null ? "" : detail) + suffix);
        return true;
    }

    private record Window(Instant until, int suppressed) {
    }

    private static final int MAX_THROTTLE_KEYS = 10_000;
    private final Map<String, Window> throttle = new ConcurrentHashMap<>();

    public List<AuditEntry> recent(int limit) {
        return jdbc.sql("SELECT occurred_at, actor, action, target, detail FROM audit_log ORDER BY id DESC LIMIT :limit")
                .param("limit", limit)
                .query((rs, n) -> new AuditEntry(
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("actor"), rs.getString("action"), rs.getString("target"), rs.getString("detail")))
                .list();
    }
}

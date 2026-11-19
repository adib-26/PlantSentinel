package com.basintelligence.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.basintelligence.config.BasProperties;

/**
 * Java-side guard rails for every agent tool call. The agent is an investigator, not an administrator:
 * <ul>
 * <li>Allowlist: only the nine named tools exist; anything else is refused, and startup fails if a tool is
 * published without a policy entry.</li>
 * <li>Per-tool authorization: READ tools only read; RECORD tools write investigation records or PROPOSED
 * recommendations and can be switched off with {@code bas.agent.record-tools-enabled=false}. No tool approves,
 * schedules, changes machine settings, runs commands, reads files or exposes credentials.</li>
 * <li>Input limits, a per-tool timeout and a per-session rate limit.</li>
 * </ul>
 */
@Component
public class AgentToolPolicy {

    public enum Access {
        /** Reads application data through services. */
        READ,
        /** Writes an investigation record or a PROPOSED recommendation; never executes anything. */
        RECORD
    }

    public static final Map<String, Access> TOOLS = Map.of(
            "getMachineStatus", Access.READ,
            "getSensorReadings", Access.READ,
            "detectAnomaly", Access.READ,
            "predictFailureRisk", Access.READ,
            "getHistoricalFailures", Access.READ,
            "searchMaintenanceManual", Access.READ,
            "getMachineSpecifications", Access.READ,
            "generateMaintenanceReport", Access.RECORD,
            "createMaintenanceDraft", Access.RECORD);

    /** Tools that Kiro assistant runs (started by the web app for an engineer) may not use. */
    static final Set<String> NOT_FOR_ASSISTANT_RUNS = Set.of("createMaintenanceDraft");

    /** Words that must never appear in an agent tool name. Checked at startup as a second line of defence. */
    static final Set<String> FORBIDDEN_WORDS = Set.of("approve", "reject", "execute", "exec", "shell", "command", "file",
            "credential", "secret", "password", "setpoint", "setting", "control", "write", "delete", "sql");

    private final BasProperties.Agent config;
    private final Clock clock;
    private final Map<String, Deque<Instant>> callsBySession = new ConcurrentHashMap<>();

    public AgentToolPolicy(BasProperties properties, Clock clock) {
        this.config = properties.agent();
        this.clock = clock;
    }

    /** Fails fast if the published tools and the policy disagree. */
    public void verifyPublished(Set<String> published) {
        for (String name : published) {
            if (!TOOLS.containsKey(name)) {
                throw new IllegalStateException("Tool '" + name + "' is published over MCP but not in the agent allowlist");
            }
            String lower = name.toLowerCase();
            for (String word : FORBIDDEN_WORDS) {
                if (lower.contains(word)) {
                    throw new IllegalStateException("Tool '" + name + "' looks like an unsafe capability ('" + word + "')");
                }
            }
        }
    }

    /** Throws {@link AgentPolicyViolation} if the call is not allowed. */
    public void check(String tool, String sessionId, String arguments) {
        check(tool, sessionId, arguments, false);
    }

    /**
     * Throws {@link AgentPolicyViolation} if the call is not allowed.
     *
     * @param assistantRun the call comes from a Kiro run that the web app started for an engineer; such runs cannot
     *                     create maintenance drafts, whatever the Kiro agent file allows
     */
    public void check(String tool, String sessionId, String arguments, boolean assistantRun) {
        Access access = TOOLS.get(tool);
        if (access == null) {
            throw new AgentPolicyViolation("Tool '" + tool + "' is not allowed for the agent");
        }
        if (assistantRun && NOT_FOR_ASSISTANT_RUNS.contains(tool)) {
            throw new AgentPolicyViolation("Tool '" + tool + "' is not available to the Kiro assistant in the web app. "
                    + "The engineer can add a recommendation on the report page instead.");
        }
        if (access == Access.RECORD && !config.recordToolsEnabled()) {
            throw new AgentPolicyViolation("Tool '" + tool + "' is disabled by configuration (bas.agent.record-tools-enabled)");
        }
        if (arguments != null && arguments.length() > config.maxArgumentChars()) {
            throw new AgentPolicyViolation("Tool arguments exceed " + config.maxArgumentChars() + " characters");
        }
        String key = sessionId == null ? "no-session" : sessionId;
        Instant now = clock.instant();
        Deque<Instant> calls = callsBySession.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (calls) {
            while (!calls.isEmpty() && calls.peekFirst().isBefore(now.minus(Duration.ofMinutes(1)))) {
                calls.pollFirst();
            }
            if (calls.size() >= config.callsPerMinute()) {
                throw new AgentPolicyViolation("Rate limit: at most " + config.callsPerMinute() + " tool calls per minute");
            }
            calls.addLast(now);
        }
    }

    public Duration timeout(String tool) {
        return TOOLS.get(tool) == Access.RECORD ? config.recordToolTimeout() : config.readToolTimeout();
    }

    /** Raised when the policy refuses a call; recorded as DENIED in agent_tool_audit. */
    public static class AgentPolicyViolation extends RuntimeException {
        public AgentPolicyViolation(String message) {
            super(message);
        }
    }
}

package com.basintelligence.agent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import com.basintelligence.common.ActivityTracker;

/**
 * Wraps every MCP tool: checks the {@link AgentToolPolicy}, records the call in {@code agent_tool_audit}, runs it
 * with a timeout and records the outcome (SUCCESS, ERROR or DENIED). This is the single path by which the agent's
 * requests reach Java code.
 */
public class AuditingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final AgentAuditService audit;
    private final ActivityTracker activity;
    private final AgentToolPolicy policy;
    private final ExecutorService executor;
    private final org.springframework.transaction.PlatformTransactionManager transactions;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public AuditingToolCallback(ToolCallback delegate, AgentAuditService audit, ActivityTracker activity,
            AgentToolPolicy policy, ExecutorService executor) {
        this(delegate, audit, activity, policy, executor, null);
    }

    public AuditingToolCallback(ToolCallback delegate, AgentAuditService audit, ActivityTracker activity,
            AgentToolPolicy policy, ExecutorService executor,
            org.springframework.transaction.PlatformTransactionManager transactions) {
        this(delegate, audit, activity, policy, executor, transactions, io.micrometer.core.instrument.Metrics.globalRegistry);
    }

    public AuditingToolCallback(ToolCallback delegate, AgentAuditService audit, ActivityTracker activity,
            AgentToolPolicy policy, ExecutorService executor,
            org.springframework.transaction.PlatformTransactionManager transactions,
            io.micrometer.core.instrument.MeterRegistry meters) {
        this.meters = meters;
        this.transactions = transactions;
        this.delegate = delegate;
        this.audit = audit;
        this.activity = activity;
        this.policy = policy;
        this.executor = executor;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        activity.touch();
        String tool = getToolDefinition().name();
        String sessionId = null;
        String client = null;
        String version = null;
        var exchange = toolContext == null ? null : McpToolUtils.getMcpExchange(toolContext).orElse(null);
        if (exchange != null) {
            sessionId = exchange.sessionId();
            if (exchange.getClientInfo() != null) {
                client = exchange.getClientInfo().name();
                version = exchange.getClientInfo().version();
            }
        }
        long callId = audit.start(sessionId, client, version, BasMcpTools.AGENT, tool, toolInput);
        long started = System.nanoTime();
        try {
            policy.check(tool, sessionId, toolInput);
        } catch (AgentToolPolicy.AgentPolicyViolation e) {
            audit.finish(callId, AgentAuditService.Outcome.DENIED, e.getMessage(), elapsed(started));
            observe(tool, "DENIED", started, toolInput, null);
            throw e;
        }
        if (transactions != null && AgentToolPolicy.TOOLS.get(tool) == AgentToolPolicy.Access.RECORD) {
            return callRecordTool(tool, toolInput, callId, started);
        }
        Future<String> result = executor.submit(() -> {
            AgentCallContext.set(callId);
            try {
                // The @Tool methods do not take a ToolContext, so call the plain variant.
                return delegate.call(toolInput);
            } finally {
                AgentCallContext.clear();
            }
        });
        long timeoutMs = policy.timeout(tool).toMillis();
        try {
            String out = result.get(timeoutMs, TimeUnit.MILLISECONDS);
            audit.finish(callId, AgentAuditService.Outcome.SUCCESS, null, elapsed(started));
            observe(tool, "SUCCESS", started, toolInput, out);
            return out;
        } catch (TimeoutException e) {
            result.cancel(true);
            audit.finish(callId, AgentAuditService.Outcome.ERROR, "timed out after " + timeoutMs + " ms", elapsed(started));
            observe(tool, "TIMEOUT", started, toolInput, null);
            throw new IllegalStateException("Tool " + tool + " timed out after " + timeoutMs + " ms");
        } catch (ExecutionException e) {
            // MethodToolCallback wraps the tool's own exception; report the innermost message.
            Throwable cause = e.getCause();
            String message = rootMessage(cause);
            audit.finish(callId, AgentAuditService.Outcome.ERROR, message, elapsed(started));
            observe(tool, "ERROR", started, toolInput, null);
            throw cause instanceof RuntimeException re ? re : new IllegalStateException(message, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            audit.finish(callId, AgentAuditService.Outcome.ERROR, "interrupted", elapsed(started));
            observe(tool, "ERROR", started, toolInput, null);
            throw new IllegalStateException("Tool call interrupted", e);
        }
    }

    /**
     * Tools that write run on the caller's thread inside one transaction with a deadline. If the deadline passes,
     * the transaction rolls back, so the agent is never told "timed out" while a draft is committed behind its back.
     */
    private String callRecordTool(String tool, String toolInput, long callId, long started) {
        var template = new org.springframework.transaction.support.TransactionTemplate(transactions);
        template.setTimeout((int) Math.max(1, (policy.timeout(tool).toMillis() + 999) / 1000));
        AgentCallContext.set(callId);
        try {
            String out = template.execute(status -> delegate.call(toolInput));
            audit.finish(callId, AgentAuditService.Outcome.SUCCESS, null, elapsed(started));
            observe(tool, "SUCCESS", started, toolInput, out);
            return out;
        } catch (org.springframework.transaction.TransactionTimedOutException e) {
            audit.finish(callId, AgentAuditService.Outcome.ERROR, "timed out; nothing was saved", elapsed(started));
            observe(tool, "TIMEOUT", started, toolInput, null);
            throw new IllegalStateException("Tool " + tool + " timed out after " + policy.timeout(tool).toMillis()
                    + " ms; nothing was saved");
        } catch (RuntimeException e) {
            boolean timedOut = hasCause(e, org.springframework.transaction.TransactionTimedOutException.class);
            String message = timedOut ? "timed out; nothing was saved" : rootMessage(e);
            audit.finish(callId, AgentAuditService.Outcome.ERROR, message, elapsed(started));
            observe(tool, timedOut ? "TIMEOUT" : "ERROR", started, toolInput, null);
            if (timedOut) {
                throw new IllegalStateException("Tool " + tool + " timed out; nothing was saved");
            }
            throw e;
        } finally {
            AgentCallContext.clear();
        }
    }

    /**
     * Latency and outcome per tool, plus an estimate of the tokens the call adds to Kiro's context (arguments it
     * wrote, results it reads). Kiro's own prompt and completion tokens are counted by Kiro, not visible here.
     */
    private void observe(String tool, String outcome, long started, String input, String output) {
        meters.timer(com.basintelligence.common.BasMetrics.AGENT_TOOL_DURATION, "tool", tool, "outcome", outcome)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        meters.counter(com.basintelligence.common.BasMetrics.AGENT_TOOL_CALLS, "tool", tool, "outcome", outcome).increment();
        meters.counter(com.basintelligence.common.BasMetrics.AGENT_TOKENS_ESTIMATED, "direction", "input", "tool", tool)
                .increment(com.basintelligence.common.BasMetrics.estimateTokens(input));
        meters.counter(com.basintelligence.common.BasMetrics.AGENT_TOKENS_ESTIMATED, "direction", "output", "tool", tool)
                .increment(com.basintelligence.common.BasMetrics.estimateTokens(output));
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}

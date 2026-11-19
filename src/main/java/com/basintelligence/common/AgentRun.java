package com.basintelligence.common;

/**
 * A Kiro run that the web app started for one engineer (the Kiro assistant page). When an MCP request carries that
 * run's token, the agent's authentication gets this record as its details. Tool calls then see only the engineer's
 * machines and are audited on their behalf, linked to the Kiro message ({@code assistant_messages.id}).
 */
public record AgentRun(String username, long assistantMessageId) {

    /** Run tokens start with this, so a run token is never compared with the shared agent key. */
    public static final String TOKEN_PREFIX = "bas-run-";
}

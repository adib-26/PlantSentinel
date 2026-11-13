package com.basintelligence.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.tool.ToolCallback;

/**
 * Ordinary (in-process) tool calling: the nine agent tools as Spring AI {@link ToolCallback}s under their Java names,
 * already wrapped with the policy, timeout and audit. A Spring AI {@code ChatClient} or a test can use these directly.
 * The MCP server publishes the same callbacks under namespaced names (see {@link McpToolExposure}).
 * <p>
 * Deliberately a record rather than a {@code List<ToolCallback>} bean: Spring AI's MCP auto-configuration publishes
 * every {@code ToolCallback} bean it finds, and only the curated MCP names should be exposed.
 */
public record AgentToolset(List<ToolCallback> callbacks) {

    public Optional<ToolCallback> find(String javaName) {
        return callbacks.stream().filter(c -> c.getToolDefinition().name().equals(javaName)).findFirst();
    }
}

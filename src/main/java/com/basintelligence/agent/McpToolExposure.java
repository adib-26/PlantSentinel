package com.basintelligence.agent;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Publishes selected Spring services to MCP clients under stable, namespaced names. The Java tool names stay an
 * internal detail; MCP clients discover {@code machine_*} and {@code maintenance_*} tools with titles and behaviour
 * hints (read-only, idempotent, closed world) so a client can decide which calls to auto-approve.
 * <p>
 * Names use {@code namespace_action}. The MCP specification also allows dots ({@code machine.get_status}), but Kiro
 * only accepts {@code ^[a-zA-Z][a-zA-Z0-9_]*$} and drops other tools, and several model APIs reject dots in function
 * names, so the underscore form works with every client tested.
 */
public final class McpToolExposure {

    /** Kiro's documented rule, stricter than the MCP spec. Names are also kept short for Kiro's 64-character limit. */
    static final Pattern PORTABLE_NAME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,39}$");

    public record Exposed(String mcpName, String title) {
    }

    /** Java tool name → MCP name and title. */
    public static final Map<String, Exposed> NAMES = Map.of(
            "getMachineStatus", new Exposed("machine_get_status", "Machine status"),
            "getSensorReadings", new Exposed("machine_get_telemetry", "Machine telemetry"),
            "detectAnomaly", new Exposed("machine_detect_anomaly", "Anomaly analysis"),
            "predictFailureRisk", new Exposed("machine_get_failure_prediction", "Failure-risk prediction"),
            "getHistoricalFailures", new Exposed("machine_get_history", "Incident and maintenance history"),
            "getMachineSpecifications", new Exposed("machine_get_specifications", "Machine specifications"),
            "searchMaintenanceManual", new Exposed("maintenance_search_manual", "Search maintenance manuals"),
            "generateMaintenanceReport", new Exposed("maintenance_generate_report", "Generate maintenance report"),
            "createMaintenanceDraft", new Exposed("maintenance_create_draft", "Create maintenance draft for approval"));

    private McpToolExposure() {
    }

    public static String mcpName(String javaName) {
        Exposed e = NAMES.get(javaName);
        if (e == null) {
            throw new IllegalStateException("Tool '" + javaName + "' has no MCP name; add it to McpToolExposure.NAMES");
        }
        return e.mcpName();
    }

    public static List<McpServerFeatures.SyncToolSpecification> specifications(AgentToolset toolset) {
        return toolset.callbacks().stream().map(McpToolExposure::specification).toList();
    }

    static McpServerFeatures.SyncToolSpecification specification(ToolCallback callback) {
        String javaName = callback.getToolDefinition().name();
        Exposed exposed = NAMES.get(javaName);
        if (exposed == null || !PORTABLE_NAME.matcher(exposed.mcpName()).matches()) {
            throw new IllegalStateException("Tool '" + javaName + "' has no valid MCP name");
        }
        boolean readOnly = AgentToolPolicy.TOOLS.get(javaName) == AgentToolPolicy.Access.READ;
        McpServerFeatures.SyncToolSpecification base = McpToolUtils.toSyncToolSpecification(new Renamed(callback, exposed.mcpName()));
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(exposed.mcpName())
                .title(exposed.title())
                .description(base.tool().description())
                .inputSchema(base.tool().inputSchema())
                // readOnly, destructive, idempotent, openWorld, returnDirect
                .annotations(new McpSchema.ToolAnnotations(exposed.title(), readOnly, false, readOnly, false, null))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler(base.callHandler()).build();
    }

    /** Same behaviour as the wrapped callback, advertised under the MCP name. */
    private record Renamed(ToolCallback delegate, String name) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            ToolDefinition d = delegate.getToolDefinition();
            return DefaultToolDefinition.builder().name(name).description(d.description()).inputSchema(d.inputSchema()).build();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return delegate.call(toolInput, toolContext);
        }
    }
}

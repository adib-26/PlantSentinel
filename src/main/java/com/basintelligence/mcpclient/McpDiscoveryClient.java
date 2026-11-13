package com.basintelligence.mcpclient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * A stand-alone MCP client, independent of Kiro and of the Spring application context. It connects to BAS
 * Intelligence over the standard streamable-HTTP transport, discovers the tools and prompts, and runs a read-only
 * investigation. It proves any MCP-compatible client can use the system, not just one chat UI.
 * <p>
 * Run with {@code mcp/discover-tools.sh} while the app is running.
 */
public final class McpDiscoveryClient implements AutoCloseable {

    private final McpSyncClient client;

    public McpDiscoveryClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, Duration.ofSeconds(60));
    }

    public McpDiscoveryClient(String baseUrl, String apiKey, Duration requestTimeout) {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                .customizeRequest(request -> request.header("X-API-Key", apiKey))
                .build();
        this.client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("bas-mcp-discovery-client", "1.0.0"))
                .requestTimeout(requestTimeout)
                .initializationTimeout(requestTimeout)
                .build();
    }

    public McpSchema.InitializeResult connect() {
        return client.initialize();
    }

    public List<McpSchema.Tool> tools() {
        return client.listTools().tools();
    }

    public List<McpSchema.Prompt> prompts() {
        return client.listPrompts().prompts();
    }

    public String prompt(String name, Map<String, Object> arguments) {
        var result = client.getPrompt(new McpSchema.GetPromptRequest(name, arguments));
        return ((McpSchema.TextContent) result.messages().getFirst().content()).text();
    }

    /** Returns the tool's text result; throws if the server reports a tool error. */
    public String call(String tool, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        String text = result.content().stream().filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text()).findFirst().orElse("");
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("Tool " + tool + " failed: " + text);
        }
        return text;
    }

    @Override
    public void close() {
        client.closeGracefully();
    }

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "http://localhost:8080";
        String key = System.getenv().getOrDefault("BAS_AGENT_API_KEY", "demo-agent-key-change-me");
        String machine = args.length > 1 ? args[1] : "CNC-01";
        try (McpDiscoveryClient c = new McpDiscoveryClient(url, key)) {
            var init = c.connect();
            System.out.printf("Connected to %s %s (protocol %s)%n%n", init.serverInfo().name(), init.serverInfo().version(),
                    init.protocolVersion());
            System.out.println("Discovered tools:");
            for (McpSchema.Tool t : c.tools()) {
                var a = t.annotations();
                String hint = a == null ? "" : Boolean.TRUE.equals(a.readOnlyHint()) ? "read-only" : "records data";
                System.out.printf("  %-32s %-13s %s%n", t.name(), hint, firstSentence(t.description()));
            }
            System.out.println("\nDiscovered prompts:");
            for (McpSchema.Prompt p : c.prompts()) {
                System.out.printf("  %-32s %s%n", p.name(), p.description());
            }
            System.out.println("\nRead-only investigation of " + machine + ":");
            for (String tool : List.of("machine_get_status", "machine_get_telemetry", "machine_detect_anomaly",
                    "machine_get_failure_prediction", "machine_get_history", "machine_get_specifications")) {
                System.out.printf("  %-32s %s%n", tool, preview(c.call(tool, Map.of("machineCode", machine))));
            }
            System.out.printf("  %-32s %s%n", "maintenance_search_manual", preview(c.call("maintenance_search_manual",
                    Map.of("query", "bearing vibration rising temperature", "machineCode", machine, "topK", 2))));
        }
    }

    private static String firstSentence(String s) {
        int dot = s.indexOf(". ");
        return dot > 0 ? s.substring(0, dot + 1) : s;
    }

    private static String preview(String json) {
        String flat = json.replaceAll("\\s+", " ");
        return flat.length() > 110 ? flat.substring(0, 110) + "…" : flat;
    }
}

package com.basintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.basintelligence.agent.AgentToolset;
import com.basintelligence.agent.McpToolExposure;
import com.basintelligence.mcpclient.McpDiscoveryClient;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * A separate MCP client connects over real HTTP (random port, streamable transport, API key) and discovers the
 * investigation tools, their behaviour hints and the investigation prompt, then calls one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class McpDiscoveryIntegrationTest {

    @LocalServerPort int port;
    @Autowired AgentToolset toolset;
    @Autowired MachineService machines;

    @Test
    void separateClientDiscoversNamespacedToolsAndPrompt() {
        try (var client = new McpDiscoveryClient("http://localhost:" + port, "test-agent-key")) {
            var init = client.connect();
            assertThat(init.serverInfo().name()).isEqualTo("bas-intelligence");
            assertThat(init.instructions()).contains("machine_get_status");

            Map<String, McpSchema.Tool> tools = client.tools().stream()
                    .collect(Collectors.toMap(McpSchema.Tool::name, t -> t));
            assertThat(tools.keySet()).containsExactlyInAnyOrder("machine_get_status", "machine_get_telemetry",
                    "machine_detect_anomaly", "machine_get_failure_prediction", "machine_get_history",
                    "machine_get_specifications", "maintenance_search_manual", "maintenance_generate_report",
                    "maintenance_create_draft");
            // Kiro only accepts ^[a-zA-Z][a-zA-Z0-9_]*$; the MCP spec also allows dots, but this works everywhere.
            assertThat(tools.keySet()).allMatch(n -> n.matches("^[a-zA-Z][a-zA-Z0-9_]*$"));
            assertThat(tools.get("machine_get_status").annotations().readOnlyHint()).isTrue();
            assertThat(tools.get("maintenance_create_draft").annotations().readOnlyHint()).isFalse();
            assertThat(tools.get("maintenance_create_draft").annotations().destructiveHint()).isFalse();
            assertThat(tools.get("machine_get_telemetry").inputSchema().required()).contains("machineCode");

            assertThat(client.prompts()).extracting(McpSchema.Prompt::name).containsExactly("investigate_machine");
            assertThat(client.prompt("investigate_machine", Map.of("machineCode", "CNC-01")))
                    .contains("machine_get_failure_prediction", "AI interpretation");

            machines.register(new MachineService.RegisterMachine("MCP-01", "MCP test", MachineType.CONVEYOR, null), "test");
            assertThat(client.call("machine_get_status", Map.of("machineCode", "MCP-01"))).contains("\"code\":\"MCP-01\"");
            assertThatThrownBy(() -> client.call("machine_get_status", Map.of("machineCode", "NOPE")))
                    .hasMessageContaining("Machine not found");
        }
    }

    @Test
    void ordinaryToolCallingAndMcpExposeTheSameAllowlist() {
        Set<String> javaNames = toolset.callbacks().stream().map(c -> c.getToolDefinition().name()).collect(Collectors.toSet());
        assertThat(javaNames).isEqualTo(McpToolExposure.NAMES.keySet());
    }

    @Test
    void wrongKeyCannotConnect() {
        try (var client = new McpDiscoveryClient("http://localhost:" + port, "wrong-key")) {
            assertThatThrownBy(client::connect).isInstanceOf(RuntimeException.class);
        }
    }
}

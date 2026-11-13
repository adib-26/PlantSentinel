package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.mcpclient.McpDiscoveryClient;

/**
 * What Kiro might send over MCP that is wrong: malformed tool arguments, unknown tools, invalid JSON-RPC, and calls
 * that run past the timeout. Real HTTP, real MCP transport, real policy and audit. Read tools time out after 1 ms
 * here so a timeout happens on every read.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "bas.agent.read-tool-timeout=1ms")
@ActiveProfiles("test")
class KiroToolCallRobustnessIntegrationTest {

    @LocalServerPort int port;
    @Autowired MachineService machines;
    @Autowired JdbcClient jdbc;

    @Test
    void malformedToolArgumentsAreRefusedWithoutSideEffects() {
        machines.register(new MachineService.RegisterMachine("KIRO-01", "k", MachineType.CONVEYOR, null), "test");
        try (var kiro = new McpDiscoveryClient("http://localhost:" + port, "test-agent-key")) {
            kiro.connect();
            // wrong type: a sentence where a number is required
            assertThatThrownBy(() -> kiro.call("maintenance_create_draft", Map.of("investigationId", "the latest one",
                    "action", "x", "rationale", "E1", "priority", "HIGH"))).isInstanceOf(IllegalStateException.class);
            // invalid enum value
            assertThatThrownBy(() -> kiro.call("maintenance_create_draft", Map.of("investigationId", 1,
                    "action", "x", "rationale", "E1", "priority", "SUPER-URGENT"))).hasMessageContaining("priority");
            // required argument missing
            assertThatThrownBy(() -> kiro.call("maintenance_generate_report", Map.of("machineCode", "KIRO-01")))
                    .hasMessageContaining("question");
            // injection-shaped machine code
            assertThatThrownBy(() -> kiro.call("maintenance_generate_report", Map.of("machineCode", "KIRO-01'; DROP TABLE devices;--",
                    "question", "q", "interpretation", "i"))).hasMessageContaining("machineCode");
            // oversized argument
            Map<String, Object> huge = new HashMap<>(Map.of("machineCode", "KIRO-01", "question", "q", "interpretation", "x".repeat(9000)));
            assertThatThrownBy(() -> kiro.call("maintenance_generate_report", huge)).hasMessageContaining("exceed");
            // a tool that does not exist
            assertThatThrownBy(() -> kiro.call("approve_recommendation", Map.of("id", 1))).isInstanceOf(RuntimeException.class);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM investigations i JOIN devices d ON d.id = i.device_id WHERE d.code = 'KIRO-01'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM agent_tool_audit WHERE status = 'DENIED'").query(Long.class).single()).isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM devices WHERE code = 'KIRO-01'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void slowToolCallTimesOutAndIsAudited() {
        try (var kiro = new McpDiscoveryClient("http://localhost:" + port, "test-agent-key")) {
            kiro.connect();
            assertThatThrownBy(() -> kiro.call("machine_get_status", Map.of())).hasMessageContaining("timed out");
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM agent_tool_audit WHERE error_message LIKE 'timed out%'")
                .query(Long.class).single()).isPositive();
    }

    @Test
    void invalidJsonRpcIsRejectedWithoutA500() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (String body : new String[] {"{not json", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":\"oops\"}", ""}) {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                    .header("X-API-Key", "test-agent-key").header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as("body %s", body).isBetween(400, 499);
        }
    }
}

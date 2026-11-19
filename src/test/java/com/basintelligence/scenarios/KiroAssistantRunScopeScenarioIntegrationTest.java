package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import com.basintelligence.agent.chat.AgentRunTokens;
import com.basintelligence.agent.chat.AssistantRepository;
import com.basintelligence.common.AgentRun;
import com.basintelligence.factory.FactoryService;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.mcpclient.McpDiscoveryClient;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.TelemetryRepository;
import com.basintelligence.telemetry.TelemetrySource;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;
import com.basintelligence.users.UserAdminService;
import com.basintelligence.users.UserRole;

/**
 * "A Kiro run started from the web app reaches machines or actions its engineer may not." Real HTTP and MCP transport:
 * a run token sees only its engineer's factory, for read tools (run on another thread) and the report tool alike; it
 * cannot create drafts; every call is audited for the engineer and linked to the Kiro message; a revoked, unknown or
 * disabled account's token gets nothing. The shared agent key keeps its plant-wide view.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KiroAssistantRunScopeScenarioIntegrationTest {

    @LocalServerPort int port;
    @Autowired AgentRunTokens tokens;
    @Autowired AssistantRepository conversations;
    @Autowired MachineService machines;
    @Autowired FactoryService factories;
    @Autowired UserAdminService users;
    @Autowired TelemetryRepository telemetry;
    @Autowired JdbcClient jdbc;

    long messageId;

    @BeforeAll
    void twoFactories() {
        factories.create("RUN-PLANT-B", "Plant B", "Penang", "test");
        users.create(new UserAdminService.NewUser("run-eng-b", "Plant-B-Engineer-2026!", UserRole.ENGINEER, "RUN-PLANT-B"),
                "test");
        withReadings(machines.register(new MachineService.RegisterMachine("RUN-A1", "a", MachineType.CNC_MILL, null), "test"));
        withReadings(machines.register(new MachineService.RegisterMachine("RUN-B1", "b", MachineType.CNC_MILL, null,
                "RUN-PLANT-B"), "test"));
        long conversation = conversations.insertConversation("run-eng-b", "scope test", Instant.now());
        messageId = conversations.insertMessage(conversation, AssistantRepository.Role.KIRO, null,
                AssistantRepository.Status.RUNNING, Instant.now());
    }

    @Test
    void aRunTokenSeesOnlyItsEngineersMachinesAndCannotCreateDrafts() {
        String token = tokens.issue(new AgentRun("run-eng-b", messageId), Duration.ofMinutes(5));
        try (var kiro = client(token)) {
            kiro.connect();
            assertThat(kiro.call("machine_get_status", Map.of())).contains("RUN-B1").doesNotContain("RUN-A1");
            assertThatThrownBy(() -> kiro.call("machine_get_telemetry", Map.of("machineCode", "RUN-A1")))
                    .hasMessageContaining("RUN-A1");
            assertThatThrownBy(() -> kiro.call("maintenance_generate_report", Map.of("machineCode", "RUN-A1",
                    "question", "q", "interpretation", "i"))).hasMessageContaining("RUN-A1");
            assertThat(kiro.call("maintenance_generate_report", Map.of("machineCode", "RUN-B1",
                    "question", "Is RUN-B1 healthy?", "interpretation", "Looks normal [E1].")))
                    .contains("/investigations/");
            assertThatThrownBy(() -> kiro.call("maintenance_create_draft", Map.of("investigationId", 1, "action", "x",
                    "rationale", "E1", "priority", "LOW"))).hasMessageContaining("not available to the Kiro assistant");
        }

        var calls = jdbc.sql("SELECT tool_name, status, on_behalf_of FROM agent_tool_audit WHERE assistant_message_id = :m ORDER BY id")
                .param("m", messageId).query().listOfRows();
        assertThat(calls).hasSize(5).allSatisfy(c -> assertThat(c).containsEntry("on_behalf_of", "run-eng-b"));
        assertThat(calls).extracting(c -> c.get("status")).containsExactly("SUCCESS", "ERROR", "ERROR", "SUCCESS", "DENIED");
        assertThat(conversations.reports(messageId)).singleElement()
                .satisfies(r -> assertThat(r.machineCode()).isEqualTo("RUN-B1"));
        assertThat(jdbc.sql("SELECT requested_by FROM investigations WHERE id = :id")
                .param("id", conversations.reports(messageId).getFirst().investigationId()).query(String.class).single())
                .as("the agent's own rules keep applying").isEqualTo("kiro-agent");

        tokens.revoke(token);
        assertThatThrownBy(() -> {
            try (var kiro = client(token)) {
                kiro.connect();
            }
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void unknownTokensAreRefusedAndAuditedAndTheSharedKeyStaysPlantWide() {
        assertThatThrownBy(() -> {
            try (var kiro = client(AgentRun.TOKEN_PREFIX + "0".repeat(64))) {
                kiro.connect();
            }
        }).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'AGENT_AUTH_FAILED' AND detail LIKE '%run token%'")
                .query(Long.class).single()).isPositive();

        try (var kiro = client("test-agent-key")) {
            kiro.connect();
            assertThat(kiro.call("machine_get_status", Map.of())).contains("RUN-A1", "RUN-B1");
        }
    }

    @Test
    void aDisabledEngineersRunSeesNothing() {
        users.create(new UserAdminService.NewUser("run-eng-gone", "Plant-B-Engineer-2026!", UserRole.ENGINEER,
                "RUN-PLANT-B"), "test");
        String token = tokens.issue(new AgentRun("run-eng-gone", messageId), Duration.ofMinutes(5));
        jdbc.sql("UPDATE users SET enabled = FALSE WHERE username = 'run-eng-gone'").update();
        try (var kiro = client(token)) {
            kiro.connect();
            assertThat(kiro.call("machine_get_status", Map.of())).doesNotContain("RUN-A1", "RUN-B1");
        } finally {
            tokens.revoke(token);
        }
    }

    private McpDiscoveryClient client(String key) {
        return new McpDiscoveryClient("http://localhost:" + port, key, Duration.ofSeconds(20));
    }

    private void withReadings(Machine m) {
        Instant t0 = Instant.now().minus(Duration.ofMinutes(40)).truncatedTo(ChronoUnit.SECONDS);
        var gen = new TelemetryGenerator(new Random(m.code().hashCode()), Duration.ofMinutes(30), 0);
        for (int i = 0; i < 30; i++) {
            TelemetryReading r = gen.generate(m.id(), MachineType.CNC_MILL, Scenario.HEALTHY, t0, t0.plus(Duration.ofMinutes(i)));
            telemetry.insert(new TelemetryReading(r.machineId(), r.recordedAt(), r.temperature(), r.vibration(), r.rpm(),
                    r.torque(), r.pressure(), TelemetrySource.EDGE));
        }
    }
}

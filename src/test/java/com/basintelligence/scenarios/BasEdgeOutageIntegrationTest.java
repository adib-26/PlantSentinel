package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.basintelligence.agent.AgentToolset;
import com.basintelligence.analytics.DataQuality;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.telemetry.simulation.TelemetrySimulator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * "BAS becomes unavailable": the edge node stops sending. The outage must stay visible as missing data; the demo
 * simulator must not fill the gap, and the agent must be told the data is stale. The edge node's own
 * store-and-forward behaviour is tested against a real Mako Server by edge/test-edge-outage.sh.
 */
@SpringBootTest(properties = {"bas.simulator.enabled=true", "bas.simulator.interval=1h"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BasEdgeOutageIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MachineService machines;
    @Autowired TelemetryService telemetry;
    @Autowired TelemetrySimulator simulator;
    @Autowired AgentToolset tools;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @Test
    void edgeOutageIsVisibleAndNotMaskedBySimulatedData() throws Exception {
        var m = machines.register(new MachineService.RegisterMachine("EDGE-OUT", "Edge fed", MachineType.CONVEYOR, null), "test");
        // The gateway's last reading arrived 40 minutes ago, then BAS went down.
        Instant last = Instant.now().minus(40, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        mvc.perform(post("/api/edge/telemetry").header("X-API-Key", "test-edge-key").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"gatewayId":"mako-1","readings":[{"machineCode":"EDGE-OUT","eventId":"e1","timestamp":"%s",
                         "temperature":40,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5}]}""".formatted(last)))
                .andExpect(status().isAccepted());

        simulator.tick(); // the demo simulator runs, but must leave edge-fed machines alone

        long rows = jdbc.sql("SELECT COUNT(*) FROM telemetry WHERE device_id = :d").param("d", m.id()).query(Long.class).single();
        long simulated = jdbc.sql("SELECT COUNT(*) FROM telemetry WHERE device_id = :d AND source = 'SIMULATOR'")
                .param("d", m.id()).query(Long.class).single();
        assertThat(rows).isEqualTo(1);
        assertThat(simulated).isZero();
        assertThat(DataQuality.of(telemetry.recent(m.id(), Duration.ofHours(1)), Instant.now()).connectivity())
                .isEqualTo(DataQuality.Connectivity.OFFLINE);

        var status = json.readTree(tools.find("getMachineStatus").orElseThrow()
                .call(json.writeValueAsString(Map.of("machineCode", "EDGE-OUT"))));
        assertThat(status.at("/0/dataFreshness").asText()).startsWith("STALE");
    }

    @Test
    void simulatorStillFeedsMachinesWithoutAnEdgeNode() {
        var m = machines.register(new MachineService.RegisterMachine("SIM-ONLY", "Simulated", MachineType.CONVEYOR, null), "test");
        simulator.tick();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM telemetry WHERE device_id = :d AND source = 'SIMULATOR'")
                .param("d", m.id()).query(Long.class).single()).isEqualTo(1);
    }
}

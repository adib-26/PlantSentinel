package com.basintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.basintelligence.agent.AgentToolset;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The nine monitoring signals are recorded where the work happens and summarised at /actuator/monitoring
 * (administrators only): API latency, ingestion rate, invalid telemetry, inference latency, prediction distribution,
 * Kiro tool latency, estimated tokens, tool failures and retrieval quality. Requests carry an X-Request-Id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitoringIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MachineService machines;
    @Autowired AgentToolset tools;
    @Autowired ObjectMapper json;

    @Test
    void everySignalIsRecordedAndSummarised() throws Exception {
        machines.register(new MachineService.RegisterMachine("MON-01", "Monitored", MachineType.CNC_MILL, null), "test");
        mvc.perform(post("/api/edge/telemetry").header("X-API-Key", "test-edge-key").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"gatewayId":"g1","readings":[
                          {"machineCode":"MON-01","temperature":40,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5},
                          {"machineCode":"MON-01","vibration":1.2,"rpm":1450,"torque":25,"pressure":5},
                          {"machineCode":"MON-NONE","temperature":40,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5}]}"""))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/edge/telemetry").header("X-API-Key", "test-edge-key").contentType(MediaType.APPLICATION_JSON)
                .content("{not json")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/ml/process-failure").with(httpBasic("viewer", "viewer-pw")).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"type\":\"M\",\"airTempK\":300,\"processTempK\":310,\"rpm\":1500,\"torqueNm\":40,\"toolWearMin\":100}]"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/machines/MON-01/risk").with(httpBasic("viewer", "viewer-pw"))).andExpect(status().isOk());
        mvc.perform(get("/api/manuals/search").param("q", "spindle bearing vibration").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk());
        tools.find("getMachineStatus").orElseThrow().call("{\"machineCode\":\"MON-01\"}");
        assertThatThrownBy(() -> tools.find("getMachineStatus").orElseThrow().call("{\"machineCode\":\"NO-SUCH-99\"}"))
                .isInstanceOf(RuntimeException.class);

        JsonNode m = json.readTree(mvc.perform(get("/actuator/monitoring").with(user("admin").roles("ADMIN", "VIEWER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(m.at("/apiResponseTime/count").asLong()).isPositive();
        assertThat(m.at("/apiResponseTime/slowestEndpoints").isArray()).isTrue();
        assertThat(m.at("/telemetryIngestion/readingsPerMinute").asLong()).isPositive();
        assertThat(m.at("/telemetryIngestion/readingsTotal/EDGE").asLong()).isPositive();
        assertThat(m.at("/invalidTelemetry/MISSING_VALUE").asLong()).isPositive();
        assertThat(m.at("/invalidTelemetry/UNKNOWN_MACHINE").asLong()).isPositive();
        assertThat(m.at("/invalidTelemetry/MALFORMED_BATCH").asLong()).isPositive();
        assertThat(m.at("/mlInferenceLatency/process-failure-classifier/count").asLong()).isPositive();
        assertThat(m.at("/mlInferenceLatency/failure-risk/count").asLong()).isPositive();
        assertThat(m.at("/predictionDistribution/process-failure-classifier/outcomes").size()).isPositive();
        assertThat(m.at("/kiro/toolCallsByOutcome/SUCCESS").asLong()).isPositive();
        assertThat(m.at("/kiro/toolFailures").asLong()).isPositive();
        assertThat(m.at("/kiro/toolLatencyByTool/getMachineStatus/count").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(m.at("/kiro/estimatedTokens/output").asLong()).isPositive();
        assertThat(m.at("/ragRetrieval/searches/hit").asLong()).isPositive();
        assertThat(m.at("/ragRetrieval/benchmark/questions").asInt()).isEqualTo(22);
        assertThat(m.at("/notes").toString()).contains("estimate");
    }

    @Test
    void monitoringIsAdminOnlyAndRequestsCarryAnId() throws Exception {
        mvc.perform(get("/actuator/monitoring").with(user("engineer").roles("ENGINEER", "VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics/bas.telemetry.ingestion.rate").with(user("admin").roles("ADMIN", "VIEWER")))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(header().exists("X-Request-Id"));
        mvc.perform(get("/actuator/health").header("X-Request-Id", "edge-batch-000123"))
                .andExpect(header().string("X-Request-Id", "edge-batch-000123"));
        // ids that could inject into logs are replaced
        mvc.perform(get("/actuator/health").header("X-Request-Id", "bad id\nforged"))
                .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("forged"))));
    }
}

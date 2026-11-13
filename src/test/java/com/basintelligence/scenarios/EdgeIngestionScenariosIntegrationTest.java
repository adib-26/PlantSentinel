package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;

/** Critical failure scenarios at the edge boundary: bad sensor data, duplicates, malformed requests. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdgeIngestionScenariosIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MachineService machines;
    @Autowired JdbcClient jdbc;

    UUID machineId;

    @BeforeAll
    void machine() {
        machineId = machines.register(new MachineService.RegisterMachine("EDGE-T1", "Edge test", MachineType.CONVEYOR, null),
                "test").id();
    }

    @Test
    void missingTemperatureIsRecordedAsSensorFaultNotTelemetryNorAnomaly() throws Exception {
        long telemetryBefore = count("telemetry"), anomaliesBefore = count("anomaly_events");
        send("""
                {"gatewayId":"g1","readings":[{"machineCode":"EDGE-T1","eventId":"m-1","vibration":1.2,"rpm":1450,"torque":25,"pressure":5}]}""")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.sensorFaults").value(1))
                .andExpect(jsonPath("$.rejected[0].outcome").value("SENSOR_FAULT"))
                .andExpect(jsonPath("$.rejected[0].faults[0].type").value("MISSING_VALUE"))
                .andExpect(jsonPath("$.rejected[0].faults[0].channel").value("TEMPERATURE"));

        assertThat(count("telemetry")).isEqualTo(telemetryBefore);
        assertThat(count("anomaly_events")).isEqualTo(anomaliesBefore);
        assertThat(faults("MISSING_VALUE")).isEqualTo(1);
    }

    @Test
    void negativeAndImpossibleValuesAreSensorFaultsAndDoNotPoisonTheBatch() throws Exception {
        send("""
                {"gatewayId":"g1","readings":[
                  {"machineCode":"EDGE-T1","eventId":"n-1","temperature":40,"vibration":-3,"rpm":1450,"torque":25,"pressure":5},
                  {"machineCode":"EDGE-T1","eventId":"n-2","temperature":9999,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5},
                  {"machineCode":"EDGE-T1","eventId":"n-3","temperature":40,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5}]}""")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.sensorFaults").value(2))
                .andExpect(jsonPath("$.rejected[0].faults[0].channel").value("VIBRATION"))
                .andExpect(jsonPath("$.rejected[1].faults[0].channel").value("TEMPERATURE"));
        assertThat(faults("IMPOSSIBLE_VALUE")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void sameEventIdIsStoredOnceWithinABatchAndAcrossResends() throws Exception {
        String reading = """
                {"machineCode":"EDGE-T1","eventId":"dup-42","timestamp":"%s","temperature":41,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5}"""
                .formatted(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        send("{\"gatewayId\":\"g1\",\"readings\":[" + reading + "," + reading + "]}")
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(1));
        send("{\"gatewayId\":\"g1\",\"readings\":[" + reading + "]}") // resend after a lost response
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.rejected[0].outcome").value("DUPLICATE"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM telemetry WHERE event_id = 'dup-42'").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void readingsWithoutEventIdAreAlwaysStored() throws Exception {
        String reading = "{\"machineCode\":\"EDGE-T1\",\"temperature\":42,\"vibration\":1.2,\"rpm\":1450,\"torque\":25,\"pressure\":5}";
        send("{\"gatewayId\":\"g1\",\"readings\":[" + reading + "," + reading + "]}").andExpect(jsonPath("$.accepted").value(2));
    }

    @Test
    void timestampsFarInTheFutureOrPastAreSensorFaults() throws Exception {
        send("""
                {"gatewayId":"g1","readings":[
                  {"machineCode":"EDGE-T1","timestamp":"%s","temperature":40,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5},
                  {"machineCode":"EDGE-T1","timestamp":"2020-01-01T00:00:00Z","temperature":40,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5}]}"""
                .formatted(Instant.now().plus(1, ChronoUnit.HOURS)))
                .andExpect(jsonPath("$.sensorFaults").value(2))
                .andExpect(jsonPath("$.rejected[0].faults[0].type").value("INVALID_TIMESTAMP"));
    }

    @Test
    void unknownMachineIsReportedPerReading() throws Exception {
        send("""
                {"gatewayId":"g1","readings":[{"machineCode":"NOPE-9","temperature":40,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5}]}""")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.unknownMachines").value(1))
                .andExpect(jsonPath("$.rejected[0].outcome").value("UNKNOWN_MACHINE"));
    }

    @Test
    void malformedEnvelopesAreRejected() throws Exception {
        send("{\"readings\":[]}").andExpect(status().isBadRequest());
        send("{\"gatewayId\":\"g1\",\"readings\":[]}").andExpect(status().isBadRequest());
        send("{\"gatewayId\":\"g1\",\"readings\":[{\"temperature\":40}]}").andExpect(status().isBadRequest()); // no machine code
        send("{\"gatewayId\":\"g1\",\"readings\":[{\"machineCode\":\"EDGE-T1\",\"temperature\":\"hot\"}]}").andExpect(status().isBadRequest());
        send("{\"gatewayId\":\"g1\",\"readings\":[{\"machineCode\":\"EDGE-T1\",\"eventId\":\"bad id; drop\"}]}").andExpect(status().isBadRequest());
        send("not json").andExpect(status().isBadRequest());
        StringBuilder big = new StringBuilder("{\"gatewayId\":\"g1\",\"readings\":[");
        for (int i = 0; i < 501; i++) big.append(i == 0 ? "" : ",").append("{\"machineCode\":\"EDGE-T1\"}");
        send(big + "]}").andExpect(status().isBadRequest());
    }

    @Test
    void stuckSensorIsASensorFaultWhileTheReadingIsStillStored() throws Exception {
        var m = machines.register(new MachineService.RegisterMachine("EDGE-T2", "Stuck", MachineType.CONVEYOR, null), "test");
        StringBuilder batch = new StringBuilder("{\"gatewayId\":\"g1\",\"readings\":[");
        Instant t0 = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < 25; i++) {
            batch.append(i == 0 ? "" : ",").append("""
                    {"machineCode":"EDGE-T2","timestamp":"%s","temperature":40.0,"vibration":%s,"rpm":1450,"torque":25,"pressure":5}"""
                    .formatted(t0.plusSeconds(60L * i), 1.2 + i * 0.01));
        }
        send(batch + "]}").andExpect(jsonPath("$.accepted").value(25));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM sensor_faults WHERE device_id = :d AND fault_type = 'STUCK_VALUE' AND channel = 'TEMPERATURE'")
                .param("d", m.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM sensor_faults WHERE device_id = :d AND channel = 'VIBRATION'")
                .param("d", m.id()).query(Long.class).single()).isZero();
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/api/edge/telemetry").header("X-API-Key", "test-edge-key")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE device_id = :d").param("d", machineId).query(Long.class).single();
    }

    private long faults(String type) {
        return jdbc.sql("SELECT COUNT(*) FROM sensor_faults WHERE device_id = :d AND fault_type = :t")
                .param("d", machineId).param("t", type).query(Long.class).single();
    }
}

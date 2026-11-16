package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.basintelligence.common.Text;
import com.basintelligence.config.BasProperties;
import com.basintelligence.factory.FactoryService;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.risk.PredictionService;
import com.basintelligence.telemetry.TelemetryReading;
import com.basintelligence.telemetry.TelemetryRepository;
import com.basintelligence.telemetry.TelemetrySource;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;
import com.basintelligence.users.UserAdminService;
import com.basintelligence.users.UserRole;
import com.basintelligence.vision.InspectionService;
import com.basintelligence.vision.SampleImageGenerator;

/**
 * "A prediction is audited after its raw telemetry was pruned, or after its record or readings were changed."
 * Every stored prediction carries model name, version and input reference; the recorded input keeps it checkable
 * after retention deletes the readings, and any edit to the score, input or readings shows up as MISMATCH.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PredictionGovernanceScenarioIntegrationTest {

    static final String PW = "Plant-Governance-2026!";
    static final RequestPostProcessor VIEWER = httpBasic("viewer", "viewer-pw");

    @Autowired MockMvc mvc;
    @Autowired MachineService machines;
    @Autowired TelemetryRepository telemetry;
    @Autowired PredictionService predictions;
    @Autowired InspectionService inspections;
    @Autowired FactoryService factories;
    @Autowired UserAdminService users;
    @Autowired BasProperties properties;
    @Autowired JdbcClient jdbc;

    @BeforeAll
    void otherFactory() {
        factories.create("GOV-PLANT-B", "Second plant", "Penang", "test");
        users.create(new UserAdminService.NewUser("gov-eng-b", PW, UserRole.ENGINEER, "GOV-PLANT-B"), "test");
    }

    @Test
    void storedPredictionCarriesTheGovernanceFieldsAndVerifiesAgainstItsRawReadings() throws Exception {
        Machine m = machineWithReadings("GOV-01", Instant.now().minus(Duration.ofMinutes(40)));
        long id = predictions.predict(m, PredictionService.Trigger.SCHEDULED, null).orElseThrow();

        mvc.perform(get("/api/machines/GOV-01/predictions").with(VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].modelName").value("failure-risk"))
                .andExpect(jsonPath("$[0].modelVersion", startsWith("1.0+w")))
                .andExpect(jsonPath("$[0].inputReference", startsWith("telemetry[")));
        mvc.perform(get(verification("GOV-01", id)).with(VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.checks[*].outcome", everyItem(is("PASS"))))
                .andExpect(jsonPath("$.checks[?(@.name == 'raw telemetry')].outcome", contains("PASS")));

        assertThat(page("/analytics/GOV-01/predictions/" + id)).contains("VERIFIED", "raw telemetry", "feature digest",
                "model re-score", "simulated fault data");
    }

    @Test
    void afterRetentionPrunesTheReadingsThePredictionStillVerifiesFromItsRecordedInput() throws Exception {
        Duration retention = properties.telemetry().retention();
        Machine m = machineWithReadings("GOV-02", Instant.now().minus(retention).minus(Duration.ofDays(1)));
        long id = predictions.predict(m, PredictionService.Trigger.SCHEDULED, null).orElseThrow();
        mvc.perform(get(verification("GOV-02", id)).with(VIEWER))
                .andExpect(jsonPath("$.checks[?(@.name == 'raw telemetry')].outcome", contains("PASS")));

        // the hourly retention job's delete, limited to this machine because the test database is shared
        int pruned = jdbc.sql("DELETE FROM telemetry WHERE device_id = :d AND recorded_at < :cutoff").param("d", m.id())
                .param("cutoff", Text.utc(Instant.now().minus(retention))).update();
        assertThat(pruned).isEqualTo(30);

        mvc.perform(get(verification("GOV-02", id)).with(VIEWER))
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.checks[?(@.name == 'raw telemetry')].outcome", contains("SKIPPED")))
                .andExpect(jsonPath("$.checks[?(@.name == 'feature engineering')].outcome", contains("PASS")))
                .andExpect(jsonPath("$.checks[?(@.name == 'model re-score')].outcome", contains("PASS")));
    }

    @Test
    void changedScoreInputOrReadingsAreReportedAsMismatch() throws Exception {
        Machine m = machineWithReadings("GOV-03", Instant.now().minus(Duration.ofMinutes(40)));
        long score = predictions.predict(m, PredictionService.Trigger.SCHEDULED, null).orElseThrow();
        long input = predictions.predict(m, PredictionService.Trigger.SCHEDULED, null).orElseThrow();
        long readings = predictions.predict(m, PredictionService.Trigger.SCHEDULED, null).orElseThrow();

        jdbc.sql("UPDATE model_predictions SET probability = probability * 0.999 WHERE id = :id").param("id", score).update();
        jdbc.sql("UPDATE model_predictions SET input_json = '{not json' WHERE id = :id").param("id", input).update();

        mvc.perform(get(verification("GOV-03", score)).with(VIEWER))
                .andExpect(jsonPath("$.status").value("MISMATCH"))
                .andExpect(jsonPath("$.checks[?(@.name == 'model re-score')].outcome", contains("FAIL")));
        mvc.perform(get(verification("GOV-03", input)).with(VIEWER))
                .andExpect(jsonPath("$.status").value("MISMATCH"))
                .andExpect(jsonPath("$.checks[?(@.name == 'input record')].outcome", contains("FAIL")));

        jdbc.sql("UPDATE telemetry SET rpm = rpm + 50 WHERE device_id = :d").param("d", m.id()).update();
        mvc.perform(get(verification("GOV-03", readings)).with(VIEWER))
                .andExpect(jsonPath("$.status").value("MISMATCH"))
                .andExpect(jsonPath("$.checks[?(@.name == 'raw telemetry')].outcome", contains("FAIL")))
                .andExpect(jsonPath("$.checks[?(@.name == 'feature digest')].outcome", contains("PASS")));
    }

    @Test
    void predictionsStoredBeforeV15AreReportedAsUnverifiable() throws Exception {
        Machine m = machineWithReadings("GOV-04", Instant.now().minus(Duration.ofMinutes(40)));
        jdbc.sql("""
                INSERT INTO model_predictions (device_id, predicted_at, trigger_type, probability, risk_level, readings_used,
                    model_name, model_version, input_reference, contributions_json)
                VALUES (:d, CURRENT_TIMESTAMP, 'SCHEDULED', 0.1, 'LOW', 12, 'failure-risk', 'unrecorded',
                    'telemetry[unrecorded] n=12', '[]')""").param("d", m.id()).update();
        long id = predictions.recent(m.id(), 1).getFirst().id();

        mvc.perform(get(verification("GOV-04", id)).with(VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNVERIFIABLE"))
                .andExpect(jsonPath("$.checks[?(@.name == 'input record')].outcome", contains("SKIPPED")));
    }

    @Test
    void predictionsOfOtherMachinesOrFactoriesAnswer404() throws Exception {
        Machine a = machineWithReadings("GOV-05", Instant.now().minus(Duration.ofMinutes(40)));
        Machine b = machineWithReadings("GOV-06", Instant.now().minus(Duration.ofMinutes(40)));
        long ofA = predictions.predict(a, PredictionService.Trigger.SCHEDULED, null).orElseThrow();
        predictions.predict(b, PredictionService.Trigger.SCHEDULED, null).orElseThrow();

        mvc.perform(get(verification("GOV-06", ofA)).with(VIEWER)).andExpect(status().isNotFound());
        mvc.perform(get(verification("GOV-05", 999_999_999L)).with(VIEWER)).andExpect(status().isNotFound());
        mvc.perform(get(verification("GOV-05", ofA)).with(httpBasic("gov-eng-b", PW))).andExpect(status().isNotFound());
        mvc.perform(get("/api/machines/GOV-05/predictions").with(httpBasic("gov-eng-b", PW))).andExpect(status().isNotFound());
        mvc.perform(get(verification("GOV-05", ofA))).andExpect(status().isUnauthorized());
    }

    @Test
    void governanceViewListsRiskPredictionsAndInspectionsWithModelVersionAndInputReference() throws Exception {
        Machine m = machineWithReadings("GOV-07", Instant.now().minus(Duration.ofMinutes(40)));
        long prediction = predictions.predict(m, PredictionService.Trigger.SCHEDULED, null).orElseThrow();
        byte[] image = new SampleImageGenerator().generatePng(SampleImageGenerator.Kind.CRACK, 7);
        long inspection = inspections.inspect(m, "bearing housing", "housing.png", image, "engineer").id();

        var rows = jdbc.sql("""
                SELECT * FROM prediction_governance WHERE device_id = :d ORDER BY source_table""")
                .param("d", m.id()).query().listOfRows();

        assertThat(rows).hasSize(2);
        Map<String, Object> vision = rows.get(0);
        Map<String, Object> risk = rows.get(1);
        assertThat(vision).containsEntry("source_table", "inspections").containsEntry("prediction_id", inspection)
                .containsEntry("model_name", "defect-detector").containsEntry("trigger_type", "INSPECTION");
        assertThat((String) vision.get("model_version")).startsWith("defect-detector");
        assertThat((String) vision.get("predicted_class")).isIn("PASS", "DEFECT");
        assertThat((String) vision.get("input_reference")).startsWith("image:GOV-07-")
                .endsWith(" sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image)));
        assertThat(risk).containsEntry("source_table", "model_predictions").containsEntry("prediction_id", prediction)
                .containsEntry("model_name", "failure-risk").containsEntry("trigger_type", "SCHEDULED");
        assertThat((String) risk.get("model_version")).startsWith("1.0+w");
        assertThat((String) risk.get("predicted_class")).isIn("LOW", "MEDIUM", "HIGH", "CRITICAL");
        assertThat(((Number) risk.get("risk_score")).doubleValue()).isBetween(0.0, 1.0);
        assertThat((String) risk.get("input_reference")).matches("telemetry\\[.+/.+] n=12 features=sha256:[0-9a-f]{64}");
        assertThat(risk.get("prediction_timestamp")).isNotNull();
    }

    /** 30 readings one minute apart from {@code start}, written directly (as already-accepted telemetry). */
    private Machine machineWithReadings(String code, Instant start) {
        Machine m = machines.register(new MachineService.RegisterMachine(code, code, MachineType.CNC_MILL, null), "test");
        Instant t0 = start.truncatedTo(ChronoUnit.SECONDS);
        var gen = new TelemetryGenerator(new Random(code.hashCode()), Duration.ofMinutes(30), 0);
        for (int i = 0; i < 30; i++) {
            TelemetryReading r = gen.generate(m.id(), MachineType.CNC_MILL, Scenario.BEARING_WEAR, t0, t0.plus(Duration.ofMinutes(i)));
            telemetry.insert(new TelemetryReading(r.machineId(), r.recordedAt(), r.temperature(), r.vibration(), r.rpm(),
                    r.torque(), r.pressure(), TelemetrySource.EDGE));
        }
        return machines.get(m.id());
    }

    private String page(String path) throws Exception {
        return mvc.perform(get(path).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String verification(String code, long id) {
        return "/api/machines/" + code + "/predictions/" + id + "/verification";
    }
}

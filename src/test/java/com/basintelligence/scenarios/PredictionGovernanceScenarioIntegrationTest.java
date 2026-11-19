package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    static final TypeReference<LinkedHashMap<String, Double>> PROBABILITIES = new TypeReference<>() {
    };

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
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
                .andExpect(jsonPath("$[0].predictionId").value(id))
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
        assertThat(risk.get("predicted_value")).isNull();
    }

    /**
     * ML-lab rows: risk_score is the model's own 0-1 score and predicted_class follows from it, or it is NULL. The RUL
     * regressor's output is predicted_value; the bearing fault classifier keeps its class probabilities instead.
     */
    @Test
    void mlLabRowsStoreARiskScoreOnlyWhenTheModelHasOne() throws Exception {
        machines.register(new MachineService.RegisterMachine("GOV-08", "GOV-08", MachineType.CNC_MILL, null), "test");
        JsonNode process = postMl("/api/ml/process-failure", """
                [{"type":"M","airTempK":298.1,"processTempK":308.6,"rpm":1551,"torqueNm":42.8,"toolWearMin":0},
                 {"type":"L","airTempK":299.0,"processTempK":309.0,"rpm":1300,"torqueNm":68.0,"toolWearMin":230}]""");
        List<List<Double>> cycles = new ArrayList<>();
        for (int c = 0; c < 20; c++) { // first cycles of C-MAPSS FD001 engine 1
            cycles.add(List.of(518.67, 641.82, 1589.70, 1400.60, 14.62, 21.61, 554.36, 2388.06, 9046.19, 1.30, 47.47,
                    521.66, 2388.02, 8138.62, 8.4195, 0.03, 392.0, 2388.0, 100.00, 39.06, 23.4190));
        }
        JsonNode rul = postMl("/api/ml/remaining-useful-life", json.writeValueAsString(Map.of("cycles", cycles)));
        List<Double> samples = new ArrayList<>();
        Random random = new Random(8);
        for (int i = 0; i < 3 * 1024; i++) { // two windows with impacts, then a smooth one
            samples.add(0.05 * random.nextGaussian() + (i < 2048 && i % 100 < 3 ? 2.5 * Math.exp(-(i % 100)) : 0));
        }
        JsonNode bearing = postMl("/api/ml/bearing-vibration", json.writeValueAsString(Map.of("samples", samples)));

        Map<Long, Map<String, Object>> rows = new HashMap<>();
        jdbc.sql("SELECT * FROM prediction_governance WHERE device_id = (SELECT id FROM devices WHERE code = 'GOV-08')")
                .query().listOfRows().forEach(r -> rows.put(((Number) r.get("prediction_id")).longValue(), r));
        assertThat(rows).hasSize(2 + 1 + 3 * 2);
        assertThat(rows.values()).allSatisfy(r -> assertThat(r).containsEntry("source_table", "ml_predictions")
                .containsEntry("trigger_type", "API"));

        for (JsonNode p : process) {
            Map<String, Object> row = rows.get(p.get("predictionId").asLong());
            assertThat(row).containsEntry("model_name", "process-failure-classifier")
                    .containsEntry("predicted_class", p.get("failurePredicted").asBoolean() ? "failure" : "no_failure");
            assertThat(score(row)).isEqualTo(p.get("failureProbability").asDouble());
            assertThat(row.get("predicted_value")).isNull();
        }

        Map<String, Object> rulRow = rows.get(rul.get("predictionId").asLong());
        assertThat(rulRow).containsEntry("model_name", "remaining-useful-life-regressor");
        assertThat(rulRow.get("risk_score")).as("a regression has no probability").isNull();
        assertThat(((Number) rulRow.get("predicted_value")).doubleValue()).isEqualTo(rul.get("remainingCycles").asDouble());

        assertThat(bearing.size()).isEqualTo(3);
        for (JsonNode w : bearing) {
            Map<String, Object> detector = rows.get(w.get("anomalyPredictionId").asLong());
            assertThat(detector).containsEntry("model_name", "bearing-anomaly-detector")
                    .containsEntry("predicted_class", w.get("anomalous").asBoolean() ? "anomalous" : "normal");
            assertThat(score(detector)).isEqualTo(w.get("anomalyScore").asDouble());
            assertThat(w.get("anomalous").asBoolean()).isEqualTo(score(detector) > w.get("threshold").asDouble());

            Map<String, Object> fault = rows.get(w.get("conditionPredictionId").asLong());
            assertThat(fault).containsEntry("model_name", "bearing-fault-classifier")
                    .containsEntry("predicted_class", w.get("likelyCondition").asText());
            assertThat(fault.get("risk_score")).as("the lab validated no fault score for the classifier").isNull();
            Map<String, Double> stored = json.readValue(jdbc.sql("SELECT probabilities_json FROM ml_predictions WHERE id = :id")
                    .param("id", w.get("conditionPredictionId").asLong()).query(String.class).single(), PROBABILITIES);
            assertThat(stored).containsExactlyEntriesOf(json.convertValue(w.get("conditionProbabilities"), PROBABILITIES));
            assertThat(stored.keySet()).containsExactly("normal", "inner_race", "ball", "outer_race");
            assertThat(fault.get("predicted_class")).as("the most probable condition")
                    .isEqualTo(stored.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey());
        }
        assertThat(bearing.get(0).get("anomalous").asBoolean()).isTrue();
    }

    private JsonNode postMl(String path, String body) throws Exception {
        return json.readTree(mvc.perform(post(path).param("machine", "GOV-08").with(VIEWER)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static double score(Map<String, Object> row) {
        return ((Number) row.get("risk_score")).doubleValue();
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

package com.basintelligence;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Step 10: the trained artifacts are served through the prediction API with validation and authentication. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MlPredictionApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void listsModelCards() throws Exception {
        mvc.perform(get("/api/ml/models").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", Matchers.hasItems("process-failure-classifier",
                        "remaining-useful-life-regressor", "bearing-anomaly-detector", "bearing-fault-classifier")))
                .andExpect(jsonPath("$[0].artifactSha256", Matchers.matchesPattern("[0-9a-f]{64}")));
    }

    @Test
    void processFailureBatchSeparatesHealthyFromOverstrained() throws Exception {
        String body = """
                [{"type":"M","airTempK":298.1,"processTempK":308.6,"rpm":1551,"torqueNm":42.8,"toolWearMin":0},
                 {"type":"L","airTempK":299.0,"processTempK":309.0,"rpm":1300,"torqueNm":68.0,"toolWearMin":230}]""";
        mvc.perform(post("/api/ml/process-failure").with(httpBasic("viewer", "viewer-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].failurePredicted").value(false))
                .andExpect(jsonPath("$[1].failurePredicted").value(true))
                .andExpect(jsonPath("$[1].failureProbability", Matchers.greaterThan(0.5)));
    }

    @Test
    void rejectsInvalidInputAndAnonymousCallers() throws Exception {
        mvc.perform(post("/api/ml/process-failure").with(httpBasic("viewer", "viewer-pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"type\":\"Z\",\"airTempK\":10,\"processTempK\":308,\"rpm\":1500,\"torqueNm\":40,\"toolWearMin\":1}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/ml/process-failure").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void remainingUsefulLifeIsWithinTheModelRange() throws Exception {
        // First cycles of C-MAPSS FD001 engine 1 (a healthy, early-life engine).
        List<List<Double>> cycles = new ArrayList<>();
        for (int c = 0; c < 20; c++) {
            cycles.add(List.of(518.67, 641.82, 1589.70, 1400.60, 14.62, 21.61, 554.36, 2388.06, 9046.19, 1.30, 47.47,
                    521.66, 2388.02, 8138.62, 8.4195, 0.03, 392.0, 2388.0, 100.00, 39.06, 23.4190));
        }
        mvc.perform(post("/api/ml/remaining-useful-life").with(httpBasic("viewer", "viewer-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("cycles", cycles))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remainingCycles", Matchers.greaterThan(90.0)))
                .andExpect(jsonPath("$.cyclesObserved").value(20));
    }

    @Test
    void bearingVibrationFlagsImpactsButNotASmoothSignal() throws Exception {
        List<Double> smooth = new ArrayList<>(), impacts = new ArrayList<>();
        java.util.Random random = new java.util.Random(1);
        for (int i = 0; i < 1024; i++) {
            double noise = 0.05 * random.nextGaussian();
            smooth.add(noise);
            impacts.add(noise + (i % 100 < 3 ? 2.5 * Math.exp(-(i % 100)) : 0));
        }
        mvc.perform(post("/api/ml/bearing-vibration").with(httpBasic("viewer", "viewer-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("samples", impacts))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].anomalous").value(true))
                .andExpect(jsonPath("$[0].alarm").value(false)); // one window is not yet an alarm
        mvc.perform(post("/api/ml/bearing-vibration").with(httpBasic("viewer", "viewer-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"samples\":[0.1,0.2]}"))
                .andExpect(status().isBadRequest());
    }
}

package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.ml.ModelRegistry;
import com.basintelligence.vision.SampleImageGenerator;

/**
 * "The ML model file is missing": the app still starts, the affected features answer 503 with a clear reason, and
 * everything else (ingestion, risk, investigations) keeps working.
 */
@SpringBootTest(properties = {"bas.ml.models-location=classpath:broken-models/",
        "bas.vision.model-path=classpath:models/does-not-exist.onnx"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MissingModelFilesIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ModelRegistry registry;
    @Autowired MachineService machines;

    @Test
    void missingMlArtifactDegradesToServiceUnavailable() throws Exception {
        assertThat(registry.unavailable()).containsKey("process-failure-classifier");
        assertThat(registry.unavailable().get("process-failure-classifier")).contains("missing");
        mvc.perform(post("/api/ml/process-failure").with(httpBasic("viewer", "viewer-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"type\":\"M\",\"airTempK\":298,\"processTempK\":308,\"rpm\":1500,\"torqueNm\":40,\"toolWearMin\":10}]"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ml-lab")));
    }

    @Test
    void missingVisionModelDisablesInspectionOnly() throws Exception {
        machines.register(new MachineService.RegisterMachine("NOVISION", "x", MachineType.CONVEYOR, null), "test");
        byte[] png = new SampleImageGenerator().generatePng(SampleImageGenerator.Kind.GOOD, 1);
        mvc.perform(multipart("/api/machines/NOVISION/inspections").file(new MockMultipartFile("image", "a.png", "image/png", png))
                        .param("component", "x").with(httpBasic("engineer", "engineer-pw")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("could not be loaded")));
        mvc.perform(get("/api/machines").with(httpBasic("viewer", "viewer-pw"))).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}

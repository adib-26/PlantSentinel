package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** "A user floods an expensive AI endpoint": 429 with Retry-After, per user and per kind of operation, audited. */
@SpringBootTest(properties = {"bas.rate-limit.searches-per-minute=3", "bas.rate-limit.predictions-per-minute=2",
        "bas.rate-limit.investigations-per-minute=1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiRateLimitIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void searchesBeyondTheLimitGet429WithRetryAfterAndOtherUsersAreUnaffected() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/manuals/search").param("q", "bearing").with(httpBasic("viewer", "viewer-pw")))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/manuals/search").param("q", "bearing").with(httpBasic("viewer", "viewer-pw")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("3 per minute")));
        // another user has their own budget
        mvc.perform(get("/api/manuals/search").param("q", "bearing").with(httpBasic("engineer", "engineer-pw")))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'RATE_LIMITED' AND actor = 'viewer'")
                .query(Long.class).single()).isPositive();
    }

    @Test
    void predictionLimitAppliesToTheMlApi() throws Exception {
        String body = """
                [{"type":"M","airTempK":300,"processTempK":310,"rpm":1500,"torqueNm":40,"toolWearMin":100}]""";
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/ml/process-failure").with(httpBasic("engineer2", "engineer2-pw"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().is2xxSuccessful());
        }
        mvc.perform(post("/api/ml/process-failure").with(httpBasic("engineer2", "engineer2-pw"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
    }

    @Test
    void webInvestigationsAreLimitedToo() throws Exception {
        var admin = user("admin").roles("ADMIN", "ENGINEER", "VIEWER");
        // the first request is allowed (and fails with 422 because the question names no machine)
        mvc.perform(post("/assistant/ask").with(admin).with(csrf()).param("question", "anything wrong?"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/assistant/ask").with(admin).with(csrf()).param("question", "anything wrong?"))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
    }
}

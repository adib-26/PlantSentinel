package com.basintelligence;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

    private static final String READING = """
            {"gatewayId":"g1","readings":[{"machineCode":"NOPE","temperature":20,"vibration":1,"rpm":1000,"torque":10,"pressure":5}]}""";

    @Autowired MockMvc mvc;

    @Test
    void edgeIngestionRequiresTheEdgeKey() throws Exception {
        mvc.perform(post("/api/edge/telemetry").contentType(MediaType.APPLICATION_JSON).content(READING))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/edge/telemetry").header("X-API-Key", "wrong").contentType(MediaType.APPLICATION_JSON).content(READING))
                .andExpect(status().isUnauthorized());
        // The agent key is not valid on the edge endpoint.
        mvc.perform(post("/api/edge/telemetry").header("X-API-Key", "test-agent-key").contentType(MediaType.APPLICATION_JSON).content(READING))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/edge/telemetry").header("X-API-Key", "test-edge-key").contentType(MediaType.APPLICATION_JSON).content(READING))
                .andExpect(status().isAccepted())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.unknownMachines").value(1));
    }

    @Test
    void edgeRejectsMalformedBatches() throws Exception {
        mvc.perform(post("/api/edge/telemetry").header("X-API-Key", "test-edge-key").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gatewayId\":\"g1\",\"readings\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mcpEndpointRequiresTheAgentKey() throws Exception {
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp").header("X-API-Key", "test-edge-key").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void restApiRequiresAuthenticationAndRoles() throws Exception {
        mvc.perform(get("/api/machines")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/machines").with(httpBasic("viewer", "viewer-pw"))).andExpect(status().isOk());
        mvc.perform(get("/api/machines").with(httpBasic("viewer", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/machines").with(httpBasic("viewer", "viewer-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"X-1\",\"name\":\"x\",\"type\":\"CONVEYOR\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void restApiValidatesInput() throws Exception {
        mvc.perform(post("/api/machines").with(httpBasic("engineer", "engineer-pw")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"bad code!\",\"name\":\"\",\"type\":\"CONVEYOR\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void webUiUsesFormLoginCsrfAndSecurityHeaders() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("X-Frame-Options", "DENY"));
        mvc.perform(post("/machines").with(user("engineer").roles("ENGINEER", "VIEWER")).param("code", "X-2"))
                .andExpect(status().isForbidden()); // no CSRF token
        mvc.perform(post("/machines").with(user("viewer").roles("VIEWER")).with(csrf())
                        .param("code", "X-3").param("name", "x").param("type", "CONVEYOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublicButMetricsAreAdminOnly() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics").with(user("engineer").roles("ENGINEER", "VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(user("admin").roles("ADMIN", "VIEWER"))).andExpect(status().isOk());
    }
}

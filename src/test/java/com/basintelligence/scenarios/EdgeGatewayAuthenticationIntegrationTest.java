package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.basintelligence.factory.FactoryService;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Per-gateway device keys: "a gateway posts for a machine of another factory", "a revoked or rotated key is used",
 * "a gateway lies about its gatewayId". Keys are stored only as hashes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdgeGatewayAuthenticationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MachineService machines;
    @Autowired FactoryService factories;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;

    @BeforeAll
    void machines() {
        factories.create("GW-PLANT-B", "Gateway test plant", null, "test");
        machines.register(new MachineService.RegisterMachine("GW-A1", "A", MachineType.CNC_MILL, null), "test");
        machines.register(new MachineService.RegisterMachine("GW-B1", "B", MachineType.CNC_MILL, null, "GW-PLANT-B"), "test");
    }

    @Test
    void registeredGatewayIsBoundToItsFactoryAndIdentifiedByItsKey() throws Exception {
        String key = createGateway("gw-line-a", "PLANT-01");
        assertThat(key).startsWith("bas_gw_");

        send(key, "GW-A1", "spoofed-name").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.gatewayId").value("gw-line-a"));
        // a machine of another factory looks unregistered to this gateway
        send(key, "GW-B1", "gw-line-a").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(0)).andExpect(jsonPath("$.unknownMachines").value(1));

        // only the hash and the public prefix are stored
        var row = jdbc.sql("SELECT key_hash, key_prefix, last_used_at FROM edge_gateways WHERE code = 'gw-line-a'")
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).single();
        assertThat(row[0]).hasSize(64).isNotEqualTo(key).doesNotContain(key.substring(16));
        assertThat(key).startsWith(row[1]);
        assertThat(row[2]).isNotNull();
    }

    @Test
    void revokedAndRotatedKeysStopWorkingImmediately() throws Exception {
        String first = createGateway("gw-line-b", "GW-PLANT-B");
        send(first, "GW-B1", "x").andExpect(status().isAccepted()).andExpect(jsonPath("$.accepted").value(1));

        String rotated = json.readTree(mvc.perform(post("/api/admin/gateways/gw-line-b/rotate").with(httpBasic("admin", "admin-pw")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("apiKey").asText();
        send(first, "GW-B1", "x").andExpect(status().isUnauthorized());
        send(rotated, "GW-B1", "x").andExpect(status().isAccepted());

        mvc.perform(post("/api/admin/gateways/gw-line-b/revoke").with(httpBasic("admin", "admin-pw"))).andExpect(status().isOk());
        send(rotated, "GW-B1", "x").andExpect(status().isUnauthorized());

        long failures = jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'EDGE_AUTH_FAILED'").query(Long.class).single();
        assertThat(failures).isPositive();
        long leaked = jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE detail LIKE :k").param("k", "%" + rotated.substring(16) + "%")
                .query(Long.class).single();
        assertThat(leaked).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action IN ('GATEWAY_CREATED','GATEWAY_KEY_ROTATED','GATEWAY_REVOKED')"
                + " AND target = 'gateway:gw-line-b'").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void onlyAdministratorsManageGatewaysAndForgedKeysAreRefused() throws Exception {
        mvc.perform(post("/api/admin/gateways").with(httpBasic("engineer", "engineer-pw")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"gw-x\",\"factoryCode\":\"PLANT-01\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/gateways").with(httpBasic("admin", "admin-pw")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"x\",\"factoryCode\":\"PLANT-01\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.startsWith("code:")));
        send("bas_gw_00000000_forgedforgedforgedforgedforgedforgedforg", "GW-A1", "x").andExpect(status().isUnauthorized());
        // the shared legacy key still works for existing edge nodes
        send("test-edge-key", "GW-A1", "legacy-node").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.gatewayId").value("legacy-node"));
    }

    private String createGateway(String code, String factory) throws Exception {
        String body = mvc.perform(post("/api/admin/gateways").with(httpBasic("admin", "admin-pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"factoryCode\":\"" + factory + "\",\"description\":\"test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("apiKey").asText();
    }

    private ResultActions send(String key, String machine, String gatewayId) throws Exception {
        return mvc.perform(post("/api/edge/telemetry").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"gatewayId":"%s","readings":[{"machineCode":"%s","temperature":40,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5}]}"""
                        .formatted(gatewayId, machine)));
    }
}

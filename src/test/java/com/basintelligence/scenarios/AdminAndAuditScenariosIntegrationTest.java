package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * User administration and security auditing: weak passwords refused, BCrypt hashes stored, a disabled user or a
 * changed password locked out at once (despite the Basic-auth cache), administrators cannot lock themselves out,
 * and logins, failed logins and denied requests land in the audit log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAndAuditScenariosIntegrationTest {

    static final String PW = "Quiet-Turbine-77";

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    @Test
    void administratorCreatesUsersWithHashedPasswordsAndWeakPasswordsAreRefused() throws Exception {
        var admin = httpBasic("admin", "admin-pw");
        mvc.perform(post("/api/admin/users").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"aa-weak\",\"password\":\"short\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.startsWith("password:")));
        mvc.perform(post("/api/admin/users").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"aa-weak\",\"password\":\"aa-weak-and-long\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/users").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"Bad Name\",\"password\":\"" + PW + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/users").with(admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"aa-tech\",\"password\":\"" + PW + "\",\"role\":\"TECHNICIAN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("TECHNICIAN"))
                .andExpect(jsonPath("$.factoryCode").value("PLANT-01"))
                .andExpect(jsonPath("$.password").doesNotExist());

        String hash = jdbc.sql("SELECT password_hash FROM users WHERE username = 'aa-tech'").query(String.class).single();
        assertThat(hash).startsWith("{bcrypt}$2").doesNotContain(PW);
        mvc.perform(get("/api/machines").with(httpBasic("aa-tech", PW))).andExpect(status().isOk());

        // only administrators manage users
        mvc.perform(get("/api/admin/users").with(httpBasic("engineer", "engineer-pw"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin").with(user("engineer").roles("ENGINEER", "VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin").with(user("admin").roles("ADMIN", "ENGINEER", "VIEWER"))).andExpect(status().isOk());
    }

    @Test
    void disablingOrResettingTakesEffectImmediately() throws Exception {
        var admin = httpBasic("admin", "admin-pw");
        mvc.perform(post("/api/admin/users").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"aa-view\",\"password\":\"" + PW + "\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated());
        // warm the Basic-auth cache
        mvc.perform(get("/api/machines").with(httpBasic("aa-view", PW))).andExpect(status().isOk());

        mvc.perform(post("/api/admin/users/aa-view/password").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"Another-Pump-2026\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/machines").with(httpBasic("aa-view", PW))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/machines").with(httpBasic("aa-view", "Another-Pump-2026"))).andExpect(status().isOk());

        mvc.perform(patch("/api/admin/users/aa-view").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/machines").with(httpBasic("aa-view", "Another-Pump-2026"))).andExpect(status().isUnauthorized());

        assertThat(jdbc.sql("SELECT action FROM audit_log WHERE target = 'user:aa-view' ORDER BY id").query(String.class).list())
                .contains("USER_CREATED", "USER_PASSWORD_RESET", "USER_DISABLED");
    }

    @Test
    void administratorsCannotLockThemselvesOut() throws Exception {
        mvc.perform(patch("/api/admin/users/admin").with(httpBasic("admin", "admin-pw")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isConflict());
        mvc.perform(patch("/api/admin/users/admin").with(httpBasic("admin", "admin-pw")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"VIEWER\"}")).andExpect(status().isConflict());
    }

    @Test
    void loginsFailuresAndDenialsAreAudited() throws Exception {
        mvc.perform(formLogin("/login").user("engineer2").password("engineer2-pw"));
        mvc.perform(get("/api/machines").with(httpBasic("aa-nobody", "wrong-password"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/machines").with(httpBasic("viewer", "viewer-pw")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"AA-1\",\"name\":\"x\",\"type\":\"CONVEYOR\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/machines").with(user("engineer").roles("ENGINEER", "VIEWER")).param("code", "AA-2"))
                .andExpect(status().isForbidden());

        assertThat(count("LOGIN_SUCCEEDED", "engineer2")).isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = 'LOGIN_FAILED' AND target = 'user:aa-nobody'")
                .query(Long.class).single()).isPositive();
        assertThat(count("ACCESS_DENIED", "viewer")).isPositive();
        assertThat(count("CSRF_REJECTED", "engineer")).isPositive();
        // the attempted password never appears in the audit log
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE detail LIKE '%wrong-password%'").query(Long.class).single()).isZero();
    }

    @Test
    void adminScreenCreatesAGatewayAndShowsTheKeyOnce() throws Exception {
        var admin = user("admin").roles("ADMIN", "ENGINEER", "VIEWER");
        var result = mvc.perform(post("/admin/gateways").with(admin).with(csrf()).param("code", "aa-ui-gw")
                .param("factoryCode", "PLANT-01")).andExpect(status().is3xxRedirection()).andReturn();
        var issued = (com.basintelligence.telemetry.EdgeGatewayService.IssuedKey) result.getFlashMap().get("issuedKey");
        assertThat(issued.apiKey()).startsWith("bas_gw_");
        String page = mvc.perform(get("/admin").with(admin)).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("aa-ui-gw", issued.gateway().keyPrefix()).doesNotContain(issued.apiKey());
    }

    private long count(String action, String actor) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE action = :a AND actor = :u").param("a", action).param("u", actor)
                .query(Long.class).single();
    }
}

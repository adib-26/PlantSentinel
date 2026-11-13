package com.basintelligence.scenarios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.basintelligence.factory.FactoryService;
import com.basintelligence.investigation.InvestigationModels.RecommendationStatus;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.telemetry.simulation.Scenario;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;
import com.basintelligence.users.UserAdminService;
import com.basintelligence.users.UserRole;

/**
 * "An engineer of one factory tries to read or act on another factory's machine", and "a technician sees only the
 * machines assigned to them". Out-of-scope machines and their records answer 404, so their existence is not revealed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactoryAccessScenariosIntegrationTest {

    static final String PW = "Plant-Access-2026!";

    @Autowired MockMvc mvc;
    @Autowired MachineService machines;
    @Autowired FactoryService factories;
    @Autowired UserAdminService users;
    @Autowired TelemetryService telemetry;
    @Autowired InvestigationService investigations;

    long investigationA;
    long recommendationA;

    @BeforeAll
    void twoFactories() {
        factories.create("FA-PLANT-B", "Second plant", "Penang", "test");
        var a = machines.register(new MachineService.RegisterMachine("FA-A1", "Plant A mill", MachineType.CNC_MILL, null), "test");
        machines.register(new MachineService.RegisterMachine("FA-B1", "Plant B pump", MachineType.CENTRIFUGAL_PUMP, null, "FA-PLANT-B"), "test");
        users.create(new UserAdminService.NewUser("fa-eng-b", PW, UserRole.ENGINEER, "FA-PLANT-B"), "test");
        users.create(new UserAdminService.NewUser("fa-view-b", PW, UserRole.VIEWER, "FA-PLANT-B"), "test");
        users.create(new UserAdminService.NewUser("fa-tech-a", PW, UserRole.TECHNICIAN, null), "test");

        Instant start = Instant.now().minus(Duration.ofMinutes(100)).truncatedTo(ChronoUnit.SECONDS);
        var gen = new TelemetryGenerator(new Random(9), Duration.ofMinutes(100), 0);
        for (int i = 0; i < 100; i++) {
            telemetry.ingest(machines.get(a.id()), gen.generate(a.id(), MachineType.CNC_MILL, Scenario.BEARING_WEAR, start,
                    start.plus(Duration.ofMinutes(i))));
        }
        var view = investigations.investigate(a.id(), "q", "engineer");
        investigationA = view.investigation().id();
        recommendationA = view.recommendations().getFirst().id();
    }

    @Test
    void engineerOfAnotherFactoryCannotSeeOrActOnTheMachine() throws Exception {
        var engB = httpBasic("fa-eng-b", PW);
        String list = mvc.perform(get("/api/machines").with(engB)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).contains("FA-B1").doesNotContain("FA-A1");
        mvc.perform(get("/api/machines/FA-A1").with(engB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/machines/FA-A1/risk").with(engB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/machines/FA-B1").with(engB)).andExpect(status().isOk());
        mvc.perform(get("/api/investigations/{id}", investigationA).with(engB)).andExpect(status().isNotFound());
        mvc.perform(post("/api/recommendations/{id}/approve", recommendationA).with(engB)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/machines/FA-A1/investigations").with(engB)).andExpect(status().isNotFound());
        assertThat(investigations.view(investigationA).recommendations().getFirst().status())
                .isEqualTo(RecommendationStatus.PROPOSED);

        // The engineer of plant A and the administrator do see it.
        mvc.perform(get("/api/investigations/{id}", investigationA).with(httpBasic("engineer", "engineer-pw")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/machines/FA-B1").with(httpBasic("engineer", "engineer-pw"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/machines/FA-B1").with(httpBasic("admin", "admin-pw"))).andExpect(status().isOk());
    }

    @Test
    void machinesAreRegisteredOnlyInTheEngineersOwnFactory() throws Exception {
        var engB = httpBasic("fa-eng-b", PW);
        mvc.perform(post("/api/machines").with(engB).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"FA-B2\",\"name\":\"x\",\"type\":\"CONVEYOR\",\"factoryCode\":\"PLANT-01\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/machines").with(engB).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"FA-B3\",\"name\":\"x\",\"type\":\"CONVEYOR\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/machines/FA-B3").with(httpBasic("fa-view-b", PW))).andExpect(status().isOk());
    }

    @Test
    void webScreensAreFilteredByFactory() throws Exception {
        var engB = user("fa-eng-b").roles("ENGINEER", "VIEWER");
        String dashboard = mvc.perform(get("/").with(engB)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(dashboard).contains("FA-B1", "Second plant").doesNotContain("FA-A1");
        mvc.perform(get("/machines/FA-A1").with(engB)).andExpect(status().isNotFound());
        mvc.perform(get("/investigations/{id}", investigationA).with(engB)).andExpect(status().isNotFound());
        String investigationsPage = mvc.perform(get("/investigations").with(engB)).andReturn().getResponse().getContentAsString();
        assertThat(investigationsPage).doesNotContain("href=\"/investigations/" + investigationA + "\"");
        mvc.perform(post("/machines/FA-A1/investigations").with(engB).with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void technicianSeesOnlyAssignedMachines() throws Exception {
        var tech = httpBasic("fa-tech-a", PW);
        mvc.perform(get("/api/machines/FA-A1").with(tech)).andExpect(status().isNotFound());

        // Only administrators manage assignments, and only within the technician's factory.
        mvc.perform(post("/api/admin/machines/FA-A1/assignments").with(httpBasic("engineer", "engineer-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"fa-tech-a\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/machines/FA-B1/assignments").with(httpBasic("admin", "admin-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"fa-tech-a\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/machines/FA-A1/assignments").with(httpBasic("admin", "admin-pw"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"fa-tech-a\"}")).andExpect(status().isCreated());

        mvc.perform(get("/api/machines").with(tech)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].code").value("FA-A1"));
        mvc.perform(get("/api/machines/FA-A1").with(tech)).andExpect(status().isOk());
        mvc.perform(get("/api/investigations/{id}", investigationA).with(tech)).andExpect(status().isOk());
        // still read-only for recommendations
        mvc.perform(post("/api/recommendations/{id}/approve", recommendationA).with(tech)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
}

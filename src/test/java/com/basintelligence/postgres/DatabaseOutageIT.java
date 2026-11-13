package com.basintelligence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;

/**
 * "Neon temporarily refuses a connection": the app talks to PostgreSQL through Toxiproxy, which cuts and restores
 * the network. During the outage the edge API answers 503 with Retry-After (the edge node keeps its queue and
 * resends), health reports DOWN, and once the database is back the same request succeeds without a restart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.hikari.connection-timeout=2000",
        "spring.datasource.hikari.validation-timeout=1000",
        "spring.datasource.hikari.maximum-pool-size=3",
        "management.endpoint.health.show-components=always"})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DatabaseOutageIT {

    static final Network network = Network.newNetwork();
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withNetwork(network).withNetworkAliases("postgres");
    static final ToxiproxyContainer toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0").withNetwork(network);
    static Proxy proxy;

    static {
        postgres.start();
        toxiproxy.start();
        try {
            proxy = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort())
                    .createProxy("postgres", "0.0.0.0:8666", "postgres:5432");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + toxiproxy.getHost() + ":"
                + toxiproxy.getMappedPort(8666) + "/" + postgres.getDatabaseName() + "?connectTimeout=2&socketTimeout=5");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @Autowired MachineService machines;

    @Test
    void outageReturnsRetryable503AndRecoversWithoutRestart() throws Exception {
        machines.register(new MachineService.RegisterMachine("NEON-01", "n", MachineType.CONVEYOR, null), "test");
        assertThat(send("ev-before").statusCode()).isEqualTo(202);

        proxy.disable(); // the database stops accepting connections
        try {
            HttpResponse<String> during = send("ev-during");
            assertThat(during.statusCode()).isEqualTo(503);
            assertThat(during.headers().firstValue("Retry-After")).contains("30");
            assertThat(during.body()).contains("temporarily unavailable").doesNotContain("PSQLException", "jdbc:");
            assertThat(get("/actuator/health").statusCode()).isEqualTo(503);
        } finally {
            proxy.enable();
        }

        // the edge node resends the same reading after the outage; it is stored exactly once
        HttpResponse<String> after = awaitStatus("ev-during", 202, Duration.ofSeconds(30));
        assertThat(after.body()).contains("\"accepted\":1");
        assertThat(send("ev-during").body()).contains("\"duplicates\":1");
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> awaitStatus(String eventId, int expected, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        HttpResponse<String> last;
        do {
            last = send(eventId);
            if (last.statusCode() == expected) {
                return last;
            }
            Thread.sleep(500);
        } while (Instant.now().isBefore(deadline));
        return last;
    }

    private HttpResponse<String> send(String eventId) throws Exception {
        String body = """
                {"gatewayId":"mako-1","readings":[{"machineCode":"NEON-01","eventId":"%s","timestamp":"%s",
                 "temperature":40,"vibration":1.2,"rpm":1450,"torque":25,"pressure":5}]}"""
                .formatted(eventId, Instant.now().truncatedTo(ChronoUnit.SECONDS));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/edge/telemetry"))
                .header("X-API-Key", "test-edge-key").header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

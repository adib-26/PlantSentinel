package com.basintelligence.mcpclient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * External-API tests for the MCP client side (the role Kiro plays): WireMock stands in for an MCP server that
 * answers correctly, too slowly, or with malformed responses. The client must succeed, time out, or fail fast with
 * an error, and never hang.
 */
class McpClientWireMockTest {

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(wireMockConfig().dynamicPort().globalTemplating(true));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private void initialize() {
        server.stubFor(post(urlEqualTo("/mcp")).withRequestBody(containing("\"initialize\"")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withHeader("Mcp-Session-Id", "s1")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"{{jsonPath request.body '$.id'}}\",\"result\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{\"tools\":{}},\"serverInfo\":{\"name\":\"stub\",\"version\":\"1\"}}}")));
        server.stubFor(post(urlEqualTo("/mcp")).withRequestBody(containing("notifications/initialized"))
                .willReturn(aResponse().withStatus(202)));
    }

    private McpDiscoveryClient client(Duration timeout) {
        return new McpDiscoveryClient(server.baseUrl(), "k", timeout);
    }

    @Test
    void successfulDiscoverySendsTheApiKey() {
        initialize();
        server.stubFor(post(urlEqualTo("/mcp")).withRequestBody(containing("tools/list")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"{{jsonPath request.body '$.id'}}\",\"result\":{\"tools\":[{\"name\":"
                        + "\"machine_get_status\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}")));
        try (var c = client(Duration.ofSeconds(5))) {
            c.connect();
            assertThat(c.tools()).extracting(t -> t.name()).containsExactly("machine_get_status");
        }
        server.verify(postRequestedFor(urlEqualTo("/mcp")).withHeader("X-API-Key", equalTo("k")));
    }

    @Test
    void slowServerTimesOut() {
        initialize();
        server.stubFor(post(urlEqualTo("/mcp")).withRequestBody(containing("tools/list"))
                .willReturn(aResponse().withFixedDelay(3_000).withHeader("Content-Type", "application/json").withBody("{}")));
        try (var c = client(Duration.ofMillis(500))) {
            c.connect();
            long t0 = System.nanoTime();
            assertThatThrownBy(c::tools).isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(2_500));
        }
    }

    @Test
    void malformedResponsesFailFast() {
        server.stubFor(post(urlEqualTo("/mcp")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody("{\"jsonrpc\":\"2.0\",\"result\":")));
        try (var c = client(Duration.ofSeconds(2))) {
            assertThatThrownBy(c::connect).isInstanceOf(RuntimeException.class);
        }
        server.resetAll();
        server.stubFor(post(urlEqualTo("/mcp")).willReturn(aResponse().withStatus(500).withBody("<html>oops</html>")));
        try (var c = client(Duration.ofSeconds(2))) {
            assertThatThrownBy(c::connect).isInstanceOf(RuntimeException.class);
        }
    }
}

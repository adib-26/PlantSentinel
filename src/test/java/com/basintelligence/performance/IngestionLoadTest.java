package com.basintelligence.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;

/**
 * Lightweight load test (run with {@code ./mvnw test -Pperformance}). After a short warm-up, 8 concurrent gateways
 * post 50-reading batches for 4 machines while 200 dashboard requests (risk and analysis) arrive at the same moment. Reports throughput and latency percentiles to
 * target/performance-report.md and fails if the budgets are exceeded. Budgets are for a laptop with the embedded
 * database; Neon adds network round trips.
 */
@Tag("performance")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IngestionLoadTest {

    static final int GATEWAYS = 8;
    static final int BATCHES_PER_GATEWAY = 25;
    static final int READINGS_PER_BATCH = 50;
    /** 200 dashboard requests arriving at once while the gateways stream data. */
    static final int READERS = 200;
    static final int READS_PER_READER = 1;
    static final int READ_REQUESTS = READERS * READS_PER_READER;

    @LocalServerPort int port;
    @Autowired MachineService machines;

    @Test
    void ingestionAndReadLatencyStayWithinBudget() throws Exception {
        for (int i = 0; i < 4; i++) {
            machines.register(new MachineService.RegisterMachine("LOAD-0" + i, "load", MachineType.CNC_MILL, null), "test");
        }
        HttpClient http = HttpClient.newBuilder().executor(Executors.newVirtualThreadPerTaskExecutor()).build();
        // Warm-up: each machine has some data and the dashboard user has logged in once, as in normal use.
        Instant warm = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < 4; i++) {
            StringBuilder body = new StringBuilder("{\"gatewayId\":\"warmup\",\"readings\":[");
            for (int r = 0; r < 20; r++) {
                body.append(r == 0 ? "" : ",").append(String.format(Locale.ROOT,
                        "{\"machineCode\":\"LOAD-0%d\",\"timestamp\":\"%s\",\"temperature\":55,\"vibration\":1.8,"
                                + "\"rpm\":2400,\"torque\":35,\"pressure\":6}", i, warm.plusSeconds(r * 10L)));
            }
            assertThat(http.send(post("/api/edge/telemetry", body.append("]}").toString()), HttpResponse.BodyHandlers.discarding())
                    .statusCode()).isEqualTo(202);
        }
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/machines"))
                .header("Authorization", basic()).build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
        ConcurrentLinkedQueue<Long> writes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> reads = new ConcurrentLinkedQueue<>();
        AtomicInteger errors = new AtomicInteger();
        Instant base = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        long started = System.nanoTime();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int g = 0; g < GATEWAYS; g++) {
                final int gateway = g;
                pool.submit(() -> {
                    for (int b = 0; b < BATCHES_PER_GATEWAY; b++) {
                        StringBuilder body = new StringBuilder("{\"gatewayId\":\"load-" + gateway + "\",\"readings\":[");
                        for (int r = 0; r < READINGS_PER_BATCH; r++) {
                            int n = (b * READINGS_PER_BATCH + r);
                            body.append(r == 0 ? "" : ",").append(String.format(Locale.ROOT,
                                    "{\"machineCode\":\"LOAD-0%d\",\"eventId\":\"g%d-%d\",\"timestamp\":\"%s\",\"temperature\":%.2f,"
                                            + "\"vibration\":%.3f,\"rpm\":2400,\"torque\":35,\"pressure\":6}",
                                    gateway % 4, gateway, n, base.plusMillis(n * 500L + gateway * 37L), 55 + (n % 7) * 0.1,
                                    1.8 + (n % 5) * 0.01));
                        }
                        timed(http, post("/api/edge/telemetry", body.append("]}").toString()), writes, errors, 202);
                    }
                    return null;
                });
            }
            // Many dashboards refreshing at the same moment.
            for (int u = 0; u < READERS; u++) {
                final int user = u;
                pool.submit(() -> {
                    for (int i = 0; i < READS_PER_READER; i++) {
                        String path = user % 2 == 0 ? "/api/machines/LOAD-0" + (user % 4) + "/risk"
                                : "/api/machines/LOAD-0" + (user % 4) + "/analysis?minutes=180";
                        timed(http, HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                .header("Authorization", basic()).build(), reads, errors, -1);
                    }
                    return null;
                });
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        int readings = GATEWAYS * BATCHES_PER_GATEWAY * READINGS_PER_BATCH;
        double perSecond = readings / seconds;
        String report = """
                # Performance report (lightweight load test)

                Generated %s. Embedded H2 database, %d concurrent gateways, %d batches of %d readings, %d dashboard reads sent at once (%d users).

                | Measure | Value |
                |---|---|
                | Readings ingested | %d in %.1f s (**%.0f readings/s**) |
                | Batch POST latency p50 / p95 / max | %d / %d / %d ms |
                | Read (risk, analysis) latency p50 / p95 / max | %d / %d / %d ms |
                | Errors | %d |
                """.formatted(Instant.now(), GATEWAYS, BATCHES_PER_GATEWAY * GATEWAYS, READINGS_PER_BATCH, READ_REQUESTS, READERS,
                readings, seconds, perSecond, pct(writes, 50), pct(writes, 95), pct(writes, 100), pct(reads, 50),
                pct(reads, 95), pct(reads, 100), errors.get());
        Files.writeString(Path.of("target/performance-report.md"), report);
        System.out.println(report);

        assertThat(errors.get()).isZero();
        assertThat(perSecond).as("ingestion throughput, readings/s").isGreaterThan(500);
        assertThat(pct(writes, 95)).as("p95 batch latency ms").isLessThan(3_000);
        assertThat(pct(reads, 95)).as("p95 read latency ms during a 200-request burst").isLessThan(1_500);
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("X-API-Key", "test-edge-key")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static void timed(HttpClient http, HttpRequest request, ConcurrentLinkedQueue<Long> sink, AtomicInteger errors,
            int expected) {
        long t0 = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (expected > 0 ? r.statusCode() != expected : r.statusCode() >= 400) {
                errors.incrementAndGet();
                System.out.println("LOAD ERROR " + r.statusCode() + " " + request.uri().getPath() + " " + r.body());
            }
        } catch (Exception e) {
            errors.incrementAndGet();
            System.out.println("LOAD ERROR " + e);
        }
        sink.add((System.nanoTime() - t0) / 1_000_000);
    }

    private static long pct(ConcurrentLinkedQueue<Long> samples, int p) {
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }

    private static String basic() {
        return "Basic " + Base64.getEncoder().encodeToString("viewer:viewer-pw".getBytes());
    }
}

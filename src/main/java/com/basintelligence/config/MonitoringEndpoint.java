package com.basintelligence.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import com.basintelligence.common.BasMetrics;
import com.basintelligence.knowledge.RetrievalQualityMonitor;
import com.basintelligence.telemetry.TelemetryService;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;

/**
 * {@code GET /actuator/monitoring} (ADMIN): the nine monitoring signals in one readable JSON document, built from
 * the Micrometer registry. Counters are totals since start; rates and percentiles cover recent activity (Micrometer
 * percentiles decay over about two minutes). The same meters are what Prometheus would scrape later.
 */
@Component
@Endpoint(id = "monitoring")
public class MonitoringEndpoint {

    private final MeterRegistry meters;
    private final TelemetryService telemetry;
    private final RetrievalQualityMonitor retrieval;

    public MonitoringEndpoint(MeterRegistry meters, TelemetryService telemetry, RetrievalQualityMonitor retrieval) {
        this.meters = meters;
        this.telemetry = telemetry;
        this.retrieval = retrieval;
    }

    @ReadOperation
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apiResponseTime", api());
        out.put("telemetryIngestion", ingestion());
        out.put("invalidTelemetry", countsByTag(BasMetrics.TELEMETRY_INVALID, "reason"));
        out.put("mlInferenceLatency", timersByTag(BasMetrics.INFERENCE, "model"));
        out.put("predictionDistribution", predictions());
        out.put("kiro", kiro());
        out.put("ragRetrieval", rag());
        out.put("notes", List.of(
                "Counters are totals since the application started; percentiles cover roughly the last two minutes.",
                "Kiro token usage is an estimate (about 4 characters per token) of what tool arguments and results add "
                        + "to Kiro's context. Kiro's own model tokens are metered by Kiro and are not visible to this server.",
                "RAG benchmark: labelled questions about the bundled manuals (src/main/resources/rag/golden-queries.tsv)."));
        return out;
    }

    private Map<String, Object> api() {
        Collection<Timer> timers = meters.find("http.server.requests").timers().stream()
                .filter(t -> !String.valueOf(t.getId().getTag("uri")).startsWith("/actuator")).toList();
        Map<String, Object> api = new LinkedHashMap<>(merge(timers));
        api.put("errors5xx", timers.stream().filter(t -> "SERVER_ERROR".equals(t.getId().getTag("outcome")))
                .mapToLong(Timer::count).sum());
        List<Map<String, Object>> slowest = new ArrayList<>();
        timers.stream().filter(t -> t.count() > 0)
                .sorted(Comparator.comparingDouble((Timer t) -> t.mean(TimeUnit.MILLISECONDS)).reversed()).limit(5)
                .forEach(t -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("request", t.getId().getTag("method") + " " + t.getId().getTag("uri"));
                    row.put("status", t.getId().getTag("status"));
                    row.putAll(describe(t));
                    slowest.add(row);
                });
        api.put("slowestEndpoints", slowest);
        return api;
    }

    private Map<String, Object> ingestion() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("readingsPerMinute", telemetry.ingestionRatePerMinute());
        m.put("readingsTotal", countsByTag(BasMetrics.TELEMETRY_READINGS, "source"));
        m.put("storeLatency", merge(meters.find(BasMetrics.TELEMETRY_INGEST_DURATION).timers()));
        DistributionSummary batches = meters.find(BasMetrics.EDGE_BATCH_SIZE).summary();
        m.put("edgeBatches", batches == null ? 0 : batches.count());
        m.put("meanEdgeBatchSize", batches == null || batches.count() == 0 ? 0 : round(batches.mean()));
        return m;
    }

    private Map<String, Object> predictions() {
        Map<String, Object> byModel = new TreeMap<>();
        for (Counter c : meters.find(BasMetrics.PREDICTIONS).counters()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) byModel.computeIfAbsent(c.getId().getTag("model"),
                    k -> new LinkedHashMap<String, Object>(Map.of("outcomes", new TreeMap<String, Long>())));
            @SuppressWarnings("unchecked")
            Map<String, Long> outcomes = (Map<String, Long>) model.get("outcomes");
            outcomes.merge(c.getId().getTag("outcome"), (long) c.count(), Long::sum);
        }
        for (DistributionSummary s : meters.find(BasMetrics.PREDICTION_PROBABILITY).summaries()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> model = (Map<String, Object>) byModel.computeIfAbsent(s.getId().getTag("model"),
                    k -> new LinkedHashMap<String, Object>());
            model.put("meanProbability", round(s.mean()));
            model.put("maxProbability", round(s.max()));
        }
        return byModel;
    }

    private Map<String, Object> kiro() {
        Map<String, Object> k = new LinkedHashMap<>();
        Collection<Timer> timers = meters.find(BasMetrics.AGENT_TOOL_DURATION).timers();
        k.put("toolLatency", merge(timers));
        k.put("toolLatencyByTool", timersByTag(BasMetrics.AGENT_TOOL_DURATION, "tool"));
        Map<String, Long> outcomes = countsByTag(BasMetrics.AGENT_TOOL_CALLS, "outcome");
        k.put("toolCallsByOutcome", outcomes);
        k.put("toolFailures", outcomes.entrySet().stream().filter(e -> !e.getKey().equals("SUCCESS"))
                .mapToLong(Map.Entry::getValue).sum());
        Map<String, Object> failuresByTool = new TreeMap<>();
        meters.find(BasMetrics.AGENT_TOOL_CALLS).counters().stream()
                .filter(c -> !"SUCCESS".equals(c.getId().getTag("outcome")) && c.count() > 0)
                .forEach(c -> failuresByTool.merge(c.getId().getTag("tool") + " " + c.getId().getTag("outcome"),
                        (long) c.count(), (a, b) -> (Long) a + (Long) b));
        k.put("toolFailuresByTool", failuresByTool);
        k.put("estimatedTokens", countsByTag(BasMetrics.AGENT_TOKENS_ESTIMATED, "direction"));
        Collection<Timer> mcp = meters.find("http.server.requests").tag("uri", "/mcp").timers();
        k.put("mcpHttpRequests", merge(mcp));
        return k;
    }

    private Map<String, Object> rag() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("searches", countsByTag(BasMetrics.RAG_SEARCHES, "outcome"));
        r.put("searchLatency", merge(meters.find(BasMetrics.RAG_SEARCH_DURATION).timers()));
        DistributionSummary top = meters.find(BasMetrics.RAG_TOP_SCORE).summary();
        r.put("meanTopScore", top == null || top.count() == 0 ? null : round(top.mean()));
        r.put("agentCitations", countsByTag(BasMetrics.RAG_CITATIONS, "verified"));
        var bench = retrieval.current();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("questions", bench.questions());
        b.put("hitAt1", round(bench.hitAt1()));
        b.put("recallAt3", round(bench.recallAt3()));
        b.put("meanReciprocalRank", round(bench.meanReciprocalRank()));
        b.put("missed", bench.misses());
        r.put("benchmark", b);
        return r;
    }

    // ---- helpers ----

    private Map<String, Long> countsByTag(String name, String tag) {
        Map<String, Long> out = new TreeMap<>();
        meters.find(name).counters().forEach(c -> out.merge(String.valueOf(c.getId().getTag(tag)), (long) c.count(), Long::sum));
        return out;
    }

    private Map<String, Object> timersByTag(String name, String tag) {
        Map<String, List<Timer>> groups = new TreeMap<>();
        meters.find(name).timers().forEach(t -> groups.computeIfAbsent(String.valueOf(t.getId().getTag(tag)),
                k -> new ArrayList<>()).add(t));
        Map<String, Object> out = new LinkedHashMap<>();
        groups.forEach((k, v) -> out.put(k, merge(v)));
        return out;
    }

    /** Count, mean and max over several timers; p95 only when there is exactly one timer (percentiles do not add up). */
    private static Map<String, Object> merge(Collection<Timer> timers) {
        long count = timers.stream().mapToLong(Timer::count).sum();
        double total = timers.stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum();
        double max = timers.stream().mapToDouble(t -> t.max(TimeUnit.MILLISECONDS)).max().orElse(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", count);
        m.put("meanMs", count == 0 ? 0 : round(total / count));
        m.put("maxMs", round(max));
        if (timers.size() == 1) {
            p95(timers.iterator().next()).ifPresent(p -> m.put("p95Ms", p));
        }
        return m;
    }

    private static Map<String, Object> describe(Timer t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", t.count());
        m.put("meanMs", round(t.mean(TimeUnit.MILLISECONDS)));
        m.put("maxMs", round(t.max(TimeUnit.MILLISECONDS)));
        p95(t).ifPresent(p -> m.put("p95Ms", p));
        return m;
    }

    private static java.util.Optional<Double> p95(Timer t) {
        for (ValueAtPercentile v : t.takeSnapshot().percentileValues()) {
            if (Math.abs(v.percentile() - 0.95) < 1e-9) {
                return java.util.Optional.of(round(v.value(TimeUnit.MILLISECONDS)));
            }
        }
        return java.util.Optional.empty();
    }

    private static double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 1000) / 1000.0 : 0;
    }
}

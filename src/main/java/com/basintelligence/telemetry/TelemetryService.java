package com.basintelligence.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.analytics.AnomalyEvent;
import com.basintelligence.analytics.AnomalyRepository;
import com.basintelligence.analytics.CriticalAnomalyDetected;
import com.basintelligence.analytics.StreamingAnomalyDetector;
import com.basintelligence.analytics.TelemetryAnalysis;
import com.basintelligence.analytics.TelemetryAnalyzer;
import com.basintelligence.common.ActivityTracker;
import com.basintelligence.config.BasProperties;
import com.basintelligence.machine.HealthStatus;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineRepository;
import com.basintelligence.machine.Severity;

import io.micrometer.core.instrument.MeterRegistry;

/** Ingestion pipeline: persist reading, run streaming detectors, update machine health, record metrics. */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
    /** Suppress repeat alerts of the same kind within this period. */
    private static final Duration ALERT_COOLDOWN = Duration.ofMinutes(10);

    private final TelemetryRepository telemetry;
    private final AnomalyRepository anomalies;
    private final MachineRepository machines;
    private final TelemetryRollupService rollups;
    private final ActivityTracker activity;
    private final ApplicationEventPublisher events;
    private final BasProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;
    private final StreamingAnomalyDetector detector;
    private final TelemetryAnalyzer analyzer = new TelemetryAnalyzer();
    private final Map<UUID, Instant> lastEdgeReading = new ConcurrentHashMap<>();
    /** Machines fed by a real edge node; loaded lazily from devices.edge_managed. */
    private volatile java.util.Set<UUID> edgeManaged;
    private SensorFaultService sensorFaults;

    @org.springframework.beans.factory.annotation.Autowired
    void setSensorFaults(SensorFaultService sensorFaults) {
        this.sensorFaults = sensorFaults;
    }

    public TelemetryService(TelemetryRepository telemetry, AnomalyRepository anomalies, MachineRepository machines,
            TelemetryRollupService rollups, ActivityTracker activity, ApplicationEventPublisher events,
            BasProperties properties, MeterRegistry meters, Clock clock) {
        this.telemetry = telemetry;
        this.anomalies = anomalies;
        this.machines = machines;
        this.rollups = rollups;
        this.activity = activity;
        this.events = events;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
        this.detector = new StreamingAnomalyDetector(properties.telemetry().zscoreThreshold());
        this.ingestionRate = new com.basintelligence.common.SlidingRate(clock);
        meters.gauge(com.basintelligence.common.BasMetrics.TELEMETRY_RATE, ingestionRate,
                com.basintelligence.common.SlidingRate::perMinute);
    }

    private final com.basintelligence.common.SlidingRate ingestionRate;

    /** Readings stored in the last 60 seconds. */
    public long ingestionRatePerMinute() {
        return ingestionRate.perMinute();
    }

    @Transactional
    public HealthStatus ingest(Machine machine, TelemetryReading reading) {
        return store(machine, reading, null);
    }

    /**
     * Edge ingestion with idempotency: returns empty when {@code eventId} was already stored for this machine
     * (the gateway resent a reading after a lost response), so nothing is counted or analysed twice.
     */
    @Transactional
    public java.util.Optional<HealthStatus> ingestEdge(Machine machine, TelemetryReading reading, String eventId) {
        if (eventId != null && telemetry.existsEvent(machine.id(), eventId)) {
            meters.counter("bas.telemetry.duplicates").increment();
            meters.counter(com.basintelligence.common.BasMetrics.TELEMETRY_INVALID, "reason", "DUPLICATE").increment();
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(store(machine, reading, eventId));
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // two concurrent resends of the same event: the unique index decided
            meters.counter("bas.telemetry.duplicates").increment();
            meters.counter(com.basintelligence.common.BasMetrics.TELEMETRY_INVALID, "reason", "DUPLICATE").increment();
            return java.util.Optional.empty();
        }
    }

    private HealthStatus store(Machine machine, TelemetryReading reading, String eventId) {
        long started = System.nanoTime();
        List<TelemetryReading> baseline = telemetry.findLatestBefore(machine.id(), reading.recordedAt(),
                properties.telemetry().baselineSize());
        telemetry.insert(reading, eventId);
        detectStuckSensors(machine, reading, baseline);
        StreamingAnomalyDetector.Result result = detector.evaluate(machine.type(), reading, baseline);
        for (AnomalyEvent event : result.events()) {
            Instant since = event.detectedAt().minus(ALERT_COOLDOWN);
            if (!anomalies.existsSince(machine.id(), event.metric(), event.detector(), event.severity(), since)) {
                long id = anomalies.insert(event);
                meters.counter("bas.anomalies", "detector", event.detector().name(), "severity", event.severity().name())
                        .increment();
                log.info("Anomaly on {}: {}", machine.code(), event.message());
                if (event.severity() == Severity.CRITICAL) {
                    events.publishEvent(new CriticalAnomalyDetected(machine, id));
                }
            }
        }
        HealthStatus health = result.envelopeSeverity().toHealth();
        boolean newest = machine.lastSeenAt() == null || !reading.recordedAt().isBefore(machine.lastSeenAt());
        if (newest) {
            machines.updateHealth(machine.id(), health, reading.recordedAt());
        }
        if (reading.source() == TelemetrySource.EDGE) {
            lastEdgeReading.put(machine.id(), clock.instant());
            if (!edgeManagedIds().contains(machine.id())) {
                machines.markEdgeManaged(machine.id());
                edgeManagedIds().add(machine.id());
            }
        }
        meters.counter(com.basintelligence.common.BasMetrics.TELEMETRY_READINGS, "source", reading.source().name()).increment();
        ingestionRate.record(1);
        meters.timer(com.basintelligence.common.BasMetrics.TELEMETRY_INGEST_DURATION, "source", reading.source().name())
                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        return health;
    }

    /** A stuck sensor repeats the exact same value; that is a data problem, not machine behaviour. */
    private void detectStuckSensors(Machine machine, TelemetryReading reading, List<TelemetryReading> baseline) {
        int run = com.basintelligence.analytics.DataQuality.FLATLINE_RUN;
        if (sensorFaults == null || baseline.size() < run - 1) {
            return;
        }
        List<TelemetryReading> tail = baseline.subList(baseline.size() - (run - 1), baseline.size());
        for (com.basintelligence.machine.Metric metric : com.basintelligence.machine.Metric.values()) {
            double v = metric.of(reading);
            if (tail.stream().allMatch(r -> metric.of(r) == v)
                    && !sensorFaults.existsSince(machine.id(), SensorPlausibility.FaultType.STUCK_VALUE, metric.name(),
                            reading.recordedAt().minus(ALERT_COOLDOWN))) {
                sensorFaults.record(machine.id(), reading.recordedAt(), null, null, new SensorPlausibility.Fault(
                        SensorPlausibility.FaultType.STUCK_VALUE, metric.name(), String.valueOf(v),
                        metric.label() + " has reported exactly " + v + " for " + run + " readings in a row (stuck sensor?)"));
            }
        }
    }

    /** Machines that have ever received real edge data. The simulator never writes for them. */
    public boolean isEdgeManaged(UUID machineId) {
        return edgeManagedIds().contains(machineId);
    }

    private java.util.Set<UUID> edgeManagedIds() {
        java.util.Set<UUID> ids = edgeManaged;
        if (ids == null) {
            ids = java.util.concurrent.ConcurrentHashMap.newKeySet();
            ids.addAll(machines.edgeManagedIds());
            edgeManaged = ids;
        }
        return ids;
    }

    /** True if a real edge node reported for this machine recently, so the simulator should stay quiet. */
    public boolean hasRecentEdgeData(UUID machineId, Duration within) {
        Instant last = lastEdgeReading.get(machineId);
        return last != null && last.isAfter(clock.instant().minus(within));
    }

    public List<TelemetryReading> recent(UUID machineId, Duration window) {
        return telemetry.findSince(machineId, clock.instant().minus(window));
    }

    public List<TelemetryReading> latest(UUID machineId, int count) {
        return telemetry.findLatest(machineId, count);
    }

    public TelemetryAnalysis analyze(Machine machine, Duration window) {
        return analyzer.analyze(machine.code(), machine.type(), recent(machine.id(), window));
    }

    public List<AnomalyEvent> anomalies(UUID machineId, Duration window, int limit) {
        return anomalies.findSince(machineId, clock.instant().minus(window), limit);
    }

    /**
     * Keeps the free-tier database small: raw readings and anomalies for {@code bas.telemetry.retention},
     * rollups for {@code bas.telemetry.rollup-retention}. Runs only while the app is in use.
     */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "PT1H")
    @Transactional
    public void applyRetention() {
        if (activity.isIdle()) {
            return;
        }
        Instant now = clock.instant();
        rollups.rollupAll(); // never delete raw data that has not been rolled up
        int readings = telemetry.deleteOlderThan(now.minus(properties.telemetry().retention()));
        int events = anomalies.deleteOlderThan(now.minus(properties.telemetry().retention()));
        int buckets = rollups.deleteOlderThan(now.minus(properties.telemetry().rollupRetention()));
        if (sensorFaults != null) {
            sensorFaults.deleteOlderThan(now.minus(properties.telemetry().retention()));
        }
        if (readings + events + buckets > 0) {
            log.info("Retention removed {} readings, {} anomaly events and {} rollup buckets", readings, events, buckets);
        }
    }
}

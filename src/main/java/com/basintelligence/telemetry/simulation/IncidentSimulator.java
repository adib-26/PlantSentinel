package com.basintelligence.telemetry.simulation;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.NotFoundException;
import com.basintelligence.investigation.InvestigationService;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineRepository;
import com.basintelligence.machine.MachineService;
import com.basintelligence.telemetry.TelemetryService;

/**
 * Simulated machine incidents. An engineer picks a fault for a simulated machine; from then on this service, not the
 * background simulator, writes the machine's readings: the fault ramps from nominal to full severity over the ramp
 * time (one reading every 10 s), is held, and ends after {@link #HOLD} or when an engineer stops it.
 * <p>
 * The readings go through normal ingestion, so the streaming detectors, health status, risk model and alerts react
 * exactly as they would to real data. When the ramp completes, an investigation is started automatically; its
 * recommendations still need an engineer's approval. Everything is labelled simulated: readings have source
 * SIMULATOR, and the investigation question names the incident. Machines fed by a real BAS edge node are refused,
 * and an incident stops if real edge data arrives, so simulated data never mixes with real data.
 */
@Service
public class IncidentSimulator {

    private static final Logger log = LoggerFactory.getLogger(IncidentSimulator.class);
    static final Duration STEP = Duration.ofSeconds(10);
    /** How long the full fault is held after the ramp before the incident ends on its own. */
    static final Duration HOLD = Duration.ofMinutes(30);
    /** Catch-up after a pause or restart is bounded, so one tick never writes more than this many readings. */
    private static final int MAX_READINGS_PER_TICK = 800;
    public static final int MIN_RAMP_MINUTES = 1;
    public static final int MAX_RAMP_MINUTES = 120;

    public enum Status {
        ACTIVE, COMPLETED, STOPPED
    }

    public record Incident(long id, UUID machineId, String machineCode, Scenario fault, Duration ramp, Status status,
            String startedBy, Instant startedAt, Instant lastReadingAt, int readings, Long investigationId, String endedBy,
            Instant endedAt, String note) {

        /** 0 at the start, 1 when the fault reaches full severity. */
        public double progress(Instant now) {
            double p = (double) Duration.between(startedAt, now).toMillis() / ramp.toMillis();
            return Math.clamp(p, 0.0, 1.0);
        }

        public Instant rampEndsAt() {
            return startedAt.plus(ramp);
        }
    }

    private static final RowMapper<Incident> MAPPER = (rs, n) -> new Incident(rs.getLong("id"),
            rs.getObject("device_id", UUID.class), rs.getString("machine_code"), Scenario.valueOf(rs.getString("fault")),
            Duration.ofSeconds(rs.getInt("ramp_seconds")), Status.valueOf(rs.getString("status")),
            rs.getString("started_by"), instant(rs, "started_at"), instant(rs, "last_reading_at"), rs.getInt("readings"),
            (Long) rs.getObject("investigation_id", Long.class), rs.getString("ended_by"), instant(rs, "ended_at"),
            rs.getString("note"));

    private static final String SELECT = """
            SELECT s.*, d.code AS machine_code FROM simulated_incidents s JOIN devices d ON d.id = s.device_id""";

    private final JdbcClient jdbc;
    private final MachineService machines;
    private final MachineRepository machineRepository;
    private final TelemetryService telemetry;
    private final InvestigationService investigations;
    private final AuditService audit;
    private final Clock clock;
    private final Random random = new Random();
    /** Machines with an active incident; the background simulator skips them. Loaded lazily from the database. */
    private volatile Set<UUID> active;

    public IncidentSimulator(JdbcClient jdbc, MachineService machines, MachineRepository machineRepository,
            TelemetryService telemetry, InvestigationService investigations, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.machines = machines;
        this.machineRepository = machineRepository;
        this.telemetry = telemetry;
        this.investigations = investigations;
        this.audit = audit;
        this.clock = clock;
    }

    /** Starts an incident now. The caller must be allowed to see the machine (factory scope). */
    public Incident start(String machineCode, Scenario fault, int rampMinutes, String actor) {
        return start(machines.getByCode(machineCode), fault, rampMinutes, clock.instant(), actor);
    }

    /**
     * Starts an incident at {@code startedAt} on an already resolved machine (no access check); the next
     * {@link #advance} writes the readings from that time on. Used for backfilled demos and tests.
     */
    public synchronized Incident start(Machine machine, Scenario fault, int rampMinutes, Instant startedAt, String actor) {
        if (fault == null || fault == Scenario.HEALTHY) {
            throw new IllegalArgumentException("Choose a fault: BEARING_WEAR, COOLING_DEGRADATION, PRESSURE_LEAK or OVERLOAD");
        }
        if (rampMinutes < MIN_RAMP_MINUTES || rampMinutes > MAX_RAMP_MINUTES) {
            throw new IllegalArgumentException("The ramp must be " + MIN_RAMP_MINUTES + " to " + MAX_RAMP_MINUTES + " minutes");
        }
        if (telemetry.isEdgeManaged(machine.id()) || telemetry.hasRecentEdgeData(machine.id(), Duration.ofMinutes(5))) {
            throw new InvalidStateException(machine.code() + " receives real data from a BAS edge node; incidents can only "
                    + "be simulated on simulated machines");
        }
        if (activeFor(machine.id()).isPresent()) {
            throw new InvalidStateException("A simulated incident is already running on " + machine.code());
        }
        // Readings continue after the machine's latest reading, never in between existing ones.
        Instant first = machine.lastSeenAt() != null && machine.lastSeenAt().isAfter(startedAt)
                ? machine.lastSeenAt().plusSeconds(1) : startedAt;
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO simulated_incidents (device_id, fault, ramp_seconds, status, started_by, started_at)
                VALUES (:d, :f, :ramp, 'ACTIVE', :by, :at)""")
                .param("d", machine.id()).param("f", fault.name()).param("ramp", rampMinutes * 60)
                .param("by", actor).param("at", utc(first)).update(keys, "id");
        long id = keys.getKey().longValue();
        activeIds().add(machine.id());
        audit.record(actor, "INCIDENT_SIMULATED", "device:" + machine.code(),
                "SIM-" + id + ": " + fault + ", ramp " + rampMinutes + " min");
        log.info("Simulated incident SIM-{} started on {}: {} over {} min", id, machine.code(), fault, rampMinutes);
        return get(id);
    }

    public Incident stop(long id, String actor) {
        Incident incident = get(id);
        machines.checkAccess(incident.machineId());
        if (!end(incident, Status.STOPPED, actor, "Stopped by " + actor)) {
            throw new InvalidStateException("Simulated incident SIM-" + id + " has already ended");
        }
        audit.record(actor, "INCIDENT_STOPPED", "device:" + incident.machineCode(), "SIM-" + id);
        return get(id);
    }

    /** Writes the readings due up to {@code now} for every active incident; called by {@link IncidentScheduler}. */
    public synchronized void advance(Instant now) {
        for (Incident incident : jdbc.sql(SELECT + " WHERE s.status = 'ACTIVE' ORDER BY s.id").query(MAPPER).list()) {
            try {
                advance(incident, now);
            } catch (RuntimeException e) {
                log.warn("Simulated incident SIM-{} failed: {}", incident.id(), e.getMessage());
            }
        }
    }

    private void advance(Incident incident, Instant now) {
        Optional<Machine> found = machineRepository.findById(incident.machineId());
        if (found.isEmpty()) {
            end(incident, Status.STOPPED, "system", "Machine no longer exists");
            return;
        }
        Machine machine = found.get();
        if (telemetry.isEdgeManaged(machine.id())) {
            end(incident, Status.STOPPED, "system", "Real BAS edge data arrived; simulation stopped");
            audit.record("system", "INCIDENT_STOPPED", "device:" + machine.code(), "SIM-" + incident.id() + ": real edge data");
            return;
        }
        TelemetryGenerator generator = new TelemetryGenerator(random, incident.ramp(), 0.004);
        Instant next = incident.lastReadingAt() == null ? incident.startedAt() : incident.lastReadingAt().plus(STEP);
        Instant last = incident.lastReadingAt();
        int written = 0;
        for (Instant t = next; !t.isAfter(now) && written < MAX_READINGS_PER_TICK; t = t.plus(STEP)) {
            telemetry.ingest(machine, generator.generate(machine.id(), machine.type(), incident.fault(), incident.startedAt(), t));
            machine = machineRepository.findById(machine.id()).orElse(machine);
            last = t;
            written++;
        }
        if (written > 0) {
            jdbc.sql("UPDATE simulated_incidents SET last_reading_at = :at, readings = readings + :n WHERE id = :id")
                    .param("at", utc(last)).param("n", written).param("id", incident.id()).update();
        }
        if (incident.investigationId() == null && last != null && !last.isBefore(incident.rampEndsAt())) {
            investigate(incident);
        }
        if (last != null && !last.isBefore(incident.rampEndsAt().plus(HOLD))) {
            end(incident, Status.COMPLETED, "system", "Fault held for " + HOLD.toMinutes() + " min; simulation ended");
        }
    }

    /** The fault has reached full severity: run the same investigation an engineer would start. */
    private void investigate(Incident incident) {
        String question = "[Simulated incident SIM-" + incident.id() + ": " + incident.fault() + ", started by "
                + incident.startedBy() + "] This fault was simulated on purpose. What do the readings show?";
        try {
            long investigationId = investigations.investigate(incident.machineId(), question, incident.startedBy())
                    .investigation().id();
            jdbc.sql("UPDATE simulated_incidents SET investigation_id = :inv WHERE id = :id")
                    .param("inv", investigationId).param("id", incident.id()).update();
            audit.record("system", "INCIDENT_INVESTIGATED", "investigation:" + investigationId,
                    "SIM-" + incident.id() + " on " + incident.machineCode());
        } catch (RuntimeException e) {
            jdbc.sql("UPDATE simulated_incidents SET note = :n WHERE id = :id")
                    .param("n", com.basintelligence.common.Text.truncate("Investigation failed: " + e.getMessage(), 500))
                    .param("id", incident.id()).update();
            log.warn("Investigation for simulated incident SIM-{} failed: {}", incident.id(), e.getMessage());
        }
    }

    private boolean end(Incident incident, Status status, String actor, String note) {
        int updated = jdbc.sql("""
                UPDATE simulated_incidents SET status = :s, ended_by = :by, ended_at = :at,
                    note = COALESCE(note, :note)
                WHERE id = :id AND status = 'ACTIVE'""")
                .param("s", status.name()).param("by", actor).param("at", utc(clock.instant())).param("note", note)
                .param("id", incident.id()).update();
        activeIds().remove(incident.machineId());
        return updated == 1;
    }

    /** True while an incident writes this machine's readings; the background simulator then stays quiet. */
    public boolean isActive(UUID machineId) {
        return activeIds().contains(machineId);
    }

    public Optional<Incident> activeFor(UUID machineId) {
        return jdbc.sql(SELECT + " WHERE s.device_id = :d AND s.status = 'ACTIVE'").param("d", machineId)
                .query(MAPPER).optional();
    }

    /** Newest first, for a machine the caller may see. */
    public List<Incident> forMachine(UUID machineId, int limit) {
        return jdbc.sql(SELECT + " WHERE s.device_id = :d ORDER BY s.id DESC LIMIT :l").param("d", machineId)
                .param("l", limit).query(MAPPER).list();
    }

    public Incident get(long id) {
        return jdbc.sql(SELECT + " WHERE s.id = :id").param("id", id).query(MAPPER).optional()
                .orElseThrow(() -> new NotFoundException("Simulated incident not found: " + id));
    }

    private Set<UUID> activeIds() {
        Set<UUID> ids = active;
        if (ids == null) {
            ids = ConcurrentHashMap.newKeySet();
            ids.addAll(jdbc.sql("SELECT device_id FROM simulated_incidents WHERE status = 'ACTIVE'")
                    .query((rs, n) -> rs.getObject(1, UUID.class)).list());
            active = ids;
        }
        return ids;
    }
}

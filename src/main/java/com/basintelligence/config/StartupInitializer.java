package com.basintelligence.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.basintelligence.factory.Factory;
import com.basintelligence.factory.FactoryService;
import com.basintelligence.knowledge.ManualService;
import com.basintelligence.machine.DeviceTypeCatalog;
import com.basintelligence.machine.Machine;
import com.basintelligence.machine.MachineRepository;
import com.basintelligence.machine.MachineService;
import com.basintelligence.machine.MachineType;
import com.basintelligence.risk.PredictionService;
import com.basintelligence.telemetry.TelemetryRollupService;
import com.basintelligence.telemetry.TelemetryService;
import com.basintelligence.telemetry.simulation.DemoScenarios;
import com.basintelligence.telemetry.simulation.TelemetryGenerator;
import com.basintelligence.telemetry.simulation.TelemetrySimulator;
import com.basintelligence.users.UserAccountService;
import com.basintelligence.vision.InspectionService;
import com.basintelligence.vision.SampleImageGenerator;

/**
 * Reference data first (device types, default factory, users, bundled manuals), then, when demo seeding is
 * enabled and there are no devices, the demo plant with backfilled telemetry, rollups, predictions and inspections.
 */
@Component
public class StartupInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);
    private static final String SYSTEM = "system";
    private static final Duration HISTORY_STEP = Duration.ofMinutes(1);

    private final DeviceTypeCatalog deviceTypes;
    private final FactoryService factories;
    private final UserAccountService users;
    private final ManualService manuals;
    private final MachineService machineService;
    private final MachineRepository machineRepository;
    private final TelemetryService telemetry;
    private final TelemetryRollupService rollups;
    private final PredictionService predictions;
    private final InspectionService inspections;
    private final ObjectProvider<TelemetrySimulator> simulator;
    private final BasProperties properties;
    private final Clock clock;

    public StartupInitializer(DeviceTypeCatalog deviceTypes, FactoryService factories, UserAccountService users,
            ManualService manuals, MachineService machineService, MachineRepository machineRepository,
            TelemetryService telemetry, TelemetryRollupService rollups, PredictionService predictions,
            InspectionService inspections, ObjectProvider<TelemetrySimulator> simulator, BasProperties properties,
            Clock clock) {
        this.deviceTypes = deviceTypes;
        this.factories = factories;
        this.users = users;
        this.manuals = manuals;
        this.machineService = machineService;
        this.machineRepository = machineRepository;
        this.telemetry = telemetry;
        this.rollups = rollups;
        this.predictions = predictions;
        this.inspections = inspections;
        this.simulator = simulator;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        deviceTypes.synchronise();
        Factory factory = factories.ensureDefault();
        users.seed(properties.security(), factory.id());
        seedManuals();
        manuals.rebuildIndex();
        if (properties.demo().seed() && machineRepository.count() == 0) {
            seedPlant();
        }
    }

    private void seedManuals() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:manuals/*.md");
        for (Resource resource : resources) {
            byte[] bytes;
            try (InputStream in = resource.getInputStream()) {
                bytes = in.readAllBytes();
            }
            if (manuals.alreadyIngested(bytes)) {
                continue;
            }
            String filename = resource.getFilename();
            manuals.ingest(titleOf(bytes, filename), filename, bytes, machineTypeOf(filename), SYSTEM);
        }
    }

    private void seedPlant() {
        Duration history = properties.demo().history();
        log.info("Seeding demo plant with {} of telemetry history", history);
        TelemetryGenerator generator = new TelemetryGenerator(new Random(7), TelemetrySimulator.RAMP, 0.004);
        Instant now = clock.instant();
        for (DemoScenarios.DemoMachine dm : DemoScenarios.PLANT) {
            Machine machine = machineService.register(
                    new MachineService.RegisterMachine(dm.code(), dm.name(), dm.type(), dm.location()), SYSTEM);
            Instant start = machine.createdAt().minus(history);
            for (Instant t = start; t.isBefore(now); t = t.plus(HISTORY_STEP)) {
                telemetry.ingest(machineService.get(machine.id()),
                        generator.generate(machine.id(), dm.type(), dm.scenario(), start, t));
            }
        }
        rollups.rollupAll();
        simulator.ifAvailable(s -> DemoScenarios.PLANT.forEach(dm -> s.assign(dm.code(), dm.scenario())));
        for (Machine m : machineService.list()) {
            predictions.predict(m, PredictionService.Trigger.SCHEDULED, null);
        }
        Machine cnc = machineService.getByCode("CNC-01");
        inspections.inspectSample(cnc, "Spindle bearing housing", SampleImageGenerator.Kind.CRACK, SYSTEM);
        inspections.inspectSample(machineService.getByCode("PUMP-02"), "Impeller", SampleImageGenerator.Kind.GOOD, SYSTEM);
        log.info("Demo plant ready");
    }

    /** Uses the first Markdown heading as the title. */
    private static String titleOf(byte[] bytes, String filename) {
        return new String(bytes, StandardCharsets.UTF_8).lines()
                .filter(l -> l.startsWith("# ")).map(l -> l.substring(2).strip()).findFirst().orElse(filename);
    }

    /** Bundled manuals are named after the machine type they cover, e.g. cnc_mill-maintenance.md. */
    private static MachineType machineTypeOf(String filename) {
        for (MachineType type : MachineType.values()) {
            if (filename.toUpperCase(Locale.ROOT).startsWith(type.name())) {
                return type;
            }
        }
        return null;
    }
}

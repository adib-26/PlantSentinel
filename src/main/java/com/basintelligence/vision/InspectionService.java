package com.basintelligence.vision;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.NotFoundException;
import com.basintelligence.common.Text;
import com.basintelligence.config.BasProperties;
import com.basintelligence.machine.Machine;

import ai.onnxruntime.OrtException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;

/**
 * Visual inspection pipeline: OpenCV decode and preprocessing, ONNX Runtime classification, OpenCV
 * localisation and annotation. Images are stored on the local disk (not in the free-tier database).
 */
@Service
public class InspectionService {

    private static final Logger log = LoggerFactory.getLogger(InspectionService.class);

    private final InspectionRepository inspections;
    private final AuditService audit;
    private final BasProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;
    private final ImageProcessor images = new ImageProcessor();
    private final SampleImageGenerator samples = new SampleImageGenerator();
    /** Null when the ONNX model could not be loaded; inspections then return 503 and the rest of the app works. */
    private final DefectClassifier classifier;
    private final String classifierError;
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private final Path storage;
    private final com.basintelligence.machine.MachineService machines;

    public InspectionService(InspectionRepository inspections, AuditService audit, BasProperties properties,
            MeterRegistry meters, Clock clock, ResourceLoader resources, com.basintelligence.machine.MachineService machines) throws IOException, OrtException {
        this.machines = machines;
        this.inspections = inspections;
        this.audit = audit;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
        Resource model = resources.getResource(properties.vision().modelPath());
        DefectClassifier loaded = null;
        String error = null;
        try (InputStream in = model.getInputStream()) {
            loaded = new DefectClassifier(in.readAllBytes(), model.getFilename());
            log.info("Defect model loaded: {}", loaded.version());
        } catch (IOException | OrtException | RuntimeException e) {
            error = "Vision model " + properties.vision().modelPath() + " could not be loaded (" + e.getMessage() + ")";
            log.error("{}. Visual inspection is disabled; everything else keeps working.", error);
        }
        this.classifier = loaded;
        this.classifierError = error;
        this.storage = properties.dataDir().resolve("inspections").toAbsolutePath().normalize();
        Files.createDirectories(storage);
    }

    public Inspection inspect(Machine machine, String component, String originalFilename, byte[] bytes, String actor) {
        if (classifier == null) {
            throw new com.basintelligence.common.ServiceUnavailableException(classifierError);
        }
        if (bytes == null || bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image must be at most 10 MB");
        }
        Mat gray = images.decodeGray(bytes);
        try {
            Timer.Sample timer = Timer.start(meters);
            long started = System.nanoTime();
            double probability = classifier.defectProbability(images.toTensor(gray));
            long inferenceMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            timer.stop(meters.timer(com.basintelligence.common.BasMetrics.INFERENCE, "model", "defect-detector"));

            boolean defect = probability >= properties.vision().defectThreshold();
            List<Rect> regions = defect ? images.localiseDefects(gray) : List.of();
            String caption = (defect ? "DEFECT " : "PASS ") + Text.pct(probability)
                    + (defect ? " - " + regions.size() + " region(s)" : "");

            String stem = machine.code() + "-" + UUID.randomUUID();
            Path original = write(stem + ".img", bytes);
            Path annotated = write(stem + "-annotated.png", images.annotate(gray, regions, caption, defect));

            Inspection.Verdict verdict = defect ? Inspection.Verdict.DEFECT : Inspection.Verdict.PASS;
            Inspection draft = new Inspection(0, machine.id(), clock.instant(), component, safeName(originalFilename),
                    original.getFileName().toString(), annotated.getFileName().toString(), probability, verdict,
                    regions.size(), classifier.version(), inferenceMs, actor, DefectClassifier.sha256(bytes));
            long id = inspections.insert(draft);
            meters.counter("bas.inspections", "verdict", verdict.name()).increment();
            com.basintelligence.common.BasMetrics.recordPrediction(meters, "defect-detector", verdict.name(), probability);
            audit.record(actor, "INSPECTION", "device:" + machine.code(),
                    component + " -> " + verdict + " (" + Text.pct(probability) + ")");
            return inspections.findById(id).orElseThrow();
        } finally {
            gray.release();
        }
    }

    public Inspection inspectSample(Machine machine, String component, SampleImageGenerator.Kind kind, String actor) {
        byte[] png = samples.generatePng(kind, clock.millis());
        return inspect(machine, component, "sample-" + kind.name().toLowerCase() + ".png", png, actor);
    }

    public List<Inspection> recent(java.util.UUID machineId, Duration window, int limit) {
        return inspections.findByMachineSince(machineId, clock.instant().minus(window), limit);
    }

    public Inspection get(long id) {
        return inspections.findById(id).orElseThrow(() -> new NotFoundException("Inspection not found: " + id));
    }

    /** Annotated PNG for display. Paths come from the database, never from the request. */
    public byte[] annotatedImage(long id) {
        Inspection inspection = get(id);
        machines.checkAccess(inspection.machineId());
        Path file = storage.resolve(inspection.annotatedPath()).normalize();
        if (!file.startsWith(storage)) {
            throw new NotFoundException("Image not available");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new NotFoundException("Image not available");
        }
    }

    public String modelVersion() {
        return classifier == null ? "unavailable" : classifier.version();
    }

    public boolean available() {
        return classifier != null;
    }

    private Path write(String name, byte[] bytes) {
        try {
            return Files.write(storage.resolve(name), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String safeName(String filename) {
        return Text.safeFilename(filename);
    }

    @PreDestroy
    void close() throws OrtException {
        if (classifier != null) {
            classifier.close();
        }
    }
}

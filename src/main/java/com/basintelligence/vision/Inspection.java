package com.basintelligence.vision;

import java.time.Instant;

public record Inspection(
        long id,
        java.util.UUID machineId,
        Instant inspectedAt,
        String component,
        String originalFilename,
        String imagePath,
        String annotatedPath,
        double defectProbability,
        Verdict verdict,
        int defectRegions,
        String modelVersion,
        long inferenceMs,
        String inspectedBy) {

    public enum Verdict {
        PASS, DEFECT
    }
}

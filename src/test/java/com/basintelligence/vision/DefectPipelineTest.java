package com.basintelligence.vision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Regression test of the real OpenCV + ONNX Runtime pipeline against synthetic parts. */
class DefectPipelineTest {

    private static DefectClassifier classifier;
    private static final ImageProcessor images = new ImageProcessor();
    private static final SampleImageGenerator samples = new SampleImageGenerator();

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = DefectPipelineTest.class.getResourceAsStream("/models/defect-detector.onnx")) {
            classifier = new DefectClassifier(in.readAllBytes(), "defect-detector.onnx");
        }
    }

    @AfterAll
    static void close() throws Exception {
        classifier.close();
    }

    @Test
    void goodPartsPass() {
        for (long seed = 100; seed < 130; seed++) {
            assertThat(probability(SampleImageGenerator.Kind.GOOD, seed)).as("seed %d", seed).isLessThan(0.2);
        }
    }

    @ParameterizedTest
    @EnumSource(value = SampleImageGenerator.Kind.class, names = {"CRACK", "SCRATCH", "PITTING"})
    void defectivePartsAreFlagged(SampleImageGenerator.Kind kind) {
        for (long seed = 100; seed < 130; seed++) {
            assertThat(probability(kind, seed)).as("%s seed %d", kind, seed).isGreaterThan(0.5);
        }
    }

    @Test
    void crackIsLocalised() {
        var gray = images.decodeGray(samples.generatePng(SampleImageGenerator.Kind.CRACK, 7));
        try {
            assertThat(images.localiseDefects(gray)).isNotEmpty();
            assertThat(images.annotate(gray, images.localiseDefects(gray), "DEFECT", true)).isNotEmpty();
        } finally {
            gray.release();
        }
    }

    @Test
    void rejectsNonImages() {
        assertThatThrownBy(() -> images.decodeGray("not an image".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> images.decodeGray(new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelVersionIncludesContentHash() {
        assertThat(classifier.version()).matches("defect-detector\\.onnx@[0-9a-f]{12}");
    }

    private static double probability(SampleImageGenerator.Kind kind, long seed) {
        var gray = images.decodeGray(samples.generatePng(kind, seed));
        try {
            return classifier.defectProbability(images.toTensor(gray));
        } finally {
            gray.release();
        }
    }
}

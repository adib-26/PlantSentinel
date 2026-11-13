package com.basintelligence.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

class ModelRegistryTest {

    /** A class outside the allow-list, standing in for a malicious gadget in a tampered artifact. */
    static final class NotAModel implements Serializable {
    }

    @Test
    void loadsEveryTrainedModelAndVerifiesChecksums() throws IOException {
        var registry = new ModelRegistry(JsonMapper.builder().addModule(new JavaTimeModule()).build());

        assertThat(registry.cards()).extracting(ModelCard::name).contains(MlPredictionService.PROCESS_FAILURE,
                MlPredictionService.RUL, MlPredictionService.BEARING_ANOMALY, MlPredictionService.BEARING_FAULT);
        assertThat(registry.cards()).allSatisfy(c -> {
            assertThat(c.artifactSha256()).hasSize(64);
            assertThat(c.dataset().version()).contains("sha256:");
            assertThat(c.features()).isNotEmpty();
            assertThat(c.testMetrics()).isNotEmpty();
        });
    }

    @Test
    void deserialisationRejectsClassesOutsideTheAllowList() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new javax.swing.JLabel("not allowed"));
        }

        assertThatThrownBy(() -> ModelRegistry.deserialize(bytes.toByteArray())).isInstanceOf(InvalidClassException.class);
    }
}

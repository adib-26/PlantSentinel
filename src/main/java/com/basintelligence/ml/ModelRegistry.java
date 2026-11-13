package com.basintelligence.ml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.basintelligence.common.NotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads versioned model artifacts produced by the ML lab from {@code classpath:models/*.card.json}.
 * Each artifact's SHA-256 must match its card, and deserialisation is restricted by an allow-list filter to the
 * model wrapper and Smile classes, so a tampered or unexpected file is rejected instead of executed.
 */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);
    private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
            "maxdepth=200;maxrefs=5000000;maxbytes=100000000;"
                    + "com.basintelligence.ml.**;smile.**;java.lang.*;java.util.*;java.util.function.*;"
                    + "java.lang.invoke.SerializedLambda;[D;[I;[J;[Z;[F;[Ljava.lang.String;!*");

    public record LoadedModel(ModelCard card, Object model) {
    }

    private final Map<String, LoadedModel> models = new LinkedHashMap<>();

    /** Models whose card exists but whose artifact is missing, corrupt or fails its checksum, with the reason. */
    private final Map<String, String> unavailable = new LinkedHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ModelRegistry(ObjectMapper json,
            @org.springframework.beans.factory.annotation.Value("${bas.ml.models-location:classpath*:models/}") String location)
            throws IOException {
        Resource[] cards = new PathMatchingResourcePatternResolver().getResources(location + "*.card.json");
        for (Resource cardResource : cards) {
            ModelCard card;
            try (InputStream in = cardResource.getInputStream()) {
                card = json.readValue(in, ModelCard.class);
            } catch (IOException e) {
                log.error("Unreadable model card {}: {}", cardResource.getFilename(), e.getMessage());
                continue;
            }
            byte[] bytes;
            try (InputStream in = cardResource.createRelative(card.artifact()).getInputStream()) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                markUnavailable(card, "artifact " + card.artifact() + " is missing");
                continue;
            }
            String sha = sha256(bytes);
            if (!sha.equals(card.artifactSha256())) {
                markUnavailable(card, "artifact checksum " + sha.substring(0, 12) + "… does not match its card");
                continue;
            }
            try {
                models.put(card.name(), new LoadedModel(card, deserialize(bytes)));
            } catch (IOException e) {
                markUnavailable(card, "artifact could not be loaded: " + e.getMessage());
                continue;
            }
            log.info("Loaded model {} v{} ({}, sha256 {}…)", card.name(), card.version(), card.algorithm(),
                    sha.substring(0, 12));
        }
    }

    ModelRegistry(ObjectMapper json) throws IOException {
        this(json, "classpath*:models/");
    }

    private void markUnavailable(ModelCard card, String reason) {
        unavailable.put(card.name(), reason);
        log.error("Model {} unavailable: {}. Predictions from it return 503; retrain with ml/run-ml-lab.sh", card.name(),
                reason);
    }

    public Map<String, String> unavailable() {
        return java.util.Collections.unmodifiableMap(unavailable);
    }

    public List<ModelCard> cards() {
        return new ArrayList<>(models.values().stream().map(LoadedModel::card).toList());
    }

    public Optional<LoadedModel> find(String name) {
        return Optional.ofNullable(models.get(name));
    }

    public <T> T model(String name, Class<T> type) {
        LoadedModel m = find(name).orElseThrow(() -> new com.basintelligence.common.ServiceUnavailableException(
                "Model '" + name + "' is unavailable (" + unavailable.getOrDefault(name, "not installed")
                        + "). Train it with ml/run-ml-lab.sh"));
        return type.cast(m.model());
    }

    static Object deserialize(byte[] bytes) throws IOException {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(FILTER);
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("Unknown class in model artifact", e);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

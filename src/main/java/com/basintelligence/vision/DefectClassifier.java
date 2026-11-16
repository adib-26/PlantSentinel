package com.basintelligence.vision;

import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * ONNX Runtime (CPU) wrapper around the defect model. The session is thread-safe and created once.
 * Model contract: input "image" float32[1,1,128,128] in [0,1]; output "defect_probability" float32[1,1].
 */
public final class DefectClassifier implements AutoCloseable {

    private static final String INPUT = "image";
    private static final long[] SHAPE = {1, 1, ImageProcessor.MODEL_INPUT, ImageProcessor.MODEL_INPUT};

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String version;

    public DefectClassifier(byte[] modelBytes, String modelName) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            // Two threads is plenty for a 128x128 input and leaves the laptop responsive.
            options.setIntraOpNumThreads(2);
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            this.session = env.createSession(modelBytes, options);
        }
        this.version = modelName + "@" + sha256(modelBytes).substring(0, 12);
    }

    public double defectProbability(float[] tensor) {
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), SHAPE);
                OrtSession.Result result = session.run(Map.of(INPUT, input))) {
            float[][] output = (float[][]) result.get(0).getValue();
            return output[0][0];
        } catch (OrtException e) {
            throw new IllegalStateException("Defect model inference failed", e);
        }
    }

    public String version() {
        return version;
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }

    /** SHA-256 (hex) of the bytes: identifies a model file or an inspected image. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

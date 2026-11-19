package com.basintelligence.common;

/**
 * Names of the application's Micrometer meters, in one place so dashboards, the {@code /actuator/monitoring}
 * summary and the code agree. Spring Boot adds {@code http.server.requests} (API response time) and the JVM, Hikari
 * and Tomcat meters on its own. See docs/monitoring.md.
 */
public final class BasMetrics {

    // Telemetry pipeline
    /** Counter, tag {@code source} (EDGE, SIMULATOR): stored readings. */
    public static final String TELEMETRY_READINGS = "bas.telemetry.readings";
    /** Gauge: stored readings in the last 60 s (readings per minute). */
    public static final String TELEMETRY_RATE = "bas.telemetry.ingestion.rate";
    /** Timer, tag {@code source}: storing one reading including the streaming detectors. */
    public static final String TELEMETRY_INGEST_DURATION = "bas.telemetry.ingest.duration";
    /** Counter, tag {@code reason}: readings or batches refused or flagged (sensor faults, unknown machine, duplicates, malformed batch). */
    public static final String TELEMETRY_INVALID = "bas.telemetry.invalid";
    /** Distribution summary: readings per edge batch. */
    public static final String EDGE_BATCH_SIZE = "bas.edge.batch.size";

    // Models
    /** Timer, tag {@code model}: model inference latency (risk model, ML lab models, defect detector). */
    public static final String INFERENCE = "bas.inference";
    /** Counter, tags {@code model} and {@code outcome}: what the models predicted (risk level, failure or not, anomalous or not, verdict). */
    public static final String PREDICTIONS = "bas.predictions";
    /** Distribution summary, tag {@code model}: predicted probabilities (0..1), with buckets every 0.1 for Prometheus. */
    public static final String PREDICTION_PROBABILITY = "bas.prediction.probability";
    /**
     * Counter, tags {@code table} (model_predictions, ml_predictions) and {@code reason} (retention, row_limit): stored
     * predictions deleted. Any {@code row_limit} count means rows went before their retention period ended.
     */
    public static final String PREDICTIONS_REMOVED = "bas.predictions.removed";

    // Kiro assistant page (Kiro CLI runs started by the web app)
    /** Counter, tag {@code outcome} (DONE, FAILED, TIMED_OUT, CANCELLED): messages Kiro answered or failed to. */
    public static final String ASSISTANT_MESSAGES = "bas.assistant.messages";
    /** Timer: how long one Kiro run took, from the engineer's message to the stored reply. */
    public static final String ASSISTANT_DURATION = "bas.assistant.duration";
    /** Distribution summary: Kiro credits per run, as Kiro CLI reports them. */
    public static final String ASSISTANT_CREDITS = "bas.assistant.credits";

    // Kiro agent over MCP
    /** Timer, tags {@code tool} and {@code outcome} (SUCCESS, ERROR, TIMEOUT, DENIED): MCP tool call latency. */
    public static final String AGENT_TOOL_DURATION = "bas.agent.tool.duration";
    /** Counter, tags {@code tool} and {@code outcome}: MCP tool calls; anything but SUCCESS is a failure. */
    public static final String AGENT_TOOL_CALLS = "bas.agent.tool.calls";
    /** Counter, tags {@code direction} (input, output) and {@code tool}: estimated tokens the tool traffic adds to Kiro's context. */
    public static final String AGENT_TOKENS_ESTIMATED = "bas.agent.tokens.estimated";

    // Retrieval (RAG)
    /** Timer, tag {@code retriever}: manual search latency. */
    public static final String RAG_SEARCH_DURATION = "bas.rag.search.duration";
    /** Counter, tags {@code retriever} and {@code outcome} (hit, empty): searches. */
    public static final String RAG_SEARCHES = "bas.rag.searches";
    /** Distribution summary, tag {@code retriever}: score of the best passage per search. */
    public static final String RAG_TOP_SCORE = "bas.rag.top.score";
    /** Counter, tag {@code verified} (true, false): evidence citations in agent interpretations that do or do not exist. */
    public static final String RAG_CITATIONS = "bas.rag.citations";
    /** Gauges: benchmark of the current manual library against labelled questions. */
    public static final String RAG_EVAL_HIT_AT_1 = "bas.rag.eval.hit.at.1";
    public static final String RAG_EVAL_RECALL_AT_3 = "bas.rag.eval.recall.at.3";
    public static final String RAG_EVAL_MRR = "bas.rag.eval.mrr";

    private BasMetrics() {
    }

    private static final double[] PROBABILITY_BUCKETS = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9};

    /** Records one model output: the outcome counter and, for probabilistic models, the probability distribution. */
    public static void recordPrediction(io.micrometer.core.instrument.MeterRegistry meters, String model, String outcome,
            Double probability) {
        meters.counter(PREDICTIONS, "model", model, "outcome", outcome).increment();
        if (probability != null && Double.isFinite(probability)) {
            io.micrometer.core.instrument.DistributionSummary.builder(PREDICTION_PROBABILITY)
                    .description("Predicted probability per model, 0..1")
                    .tag("model", model)
                    .serviceLevelObjectives(PROBABILITY_BUCKETS)
                    .register(meters)
                    .record(probability);
        }
    }

    /** Times a model call under {@code bas.inference{model=...}}. */
    public static <T> T timeInference(io.micrometer.core.instrument.MeterRegistry meters, String model,
            java.util.function.Supplier<T> work) {
        io.micrometer.core.instrument.Timer.Sample sample = io.micrometer.core.instrument.Timer.start(meters);
        try {
            return work.get();
        } finally {
            sample.stop(meters.timer(INFERENCE, "model", model));
        }
    }

    /** Rough token estimate for text exchanged with a language model: about 4 characters per token. */
    public static long estimateTokens(String text) {
        return text == null || text.isEmpty() ? 0 : (text.length() + 3) / 4;
    }
}

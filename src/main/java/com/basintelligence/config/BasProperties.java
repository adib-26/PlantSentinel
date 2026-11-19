package com.basintelligence.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Typed application settings bound from the {@code bas.*} namespace. */
@Validated
@ConfigurationProperties(prefix = "bas")
public record BasProperties(
        @NotNull Path dataDir,
        /** Background jobs pause after this long without any request. Zero disables pausing. */
        @DefaultValue("30m") Duration idlePause,
        @Valid @NotNull Security security,
        @Valid @NotNull Telemetry telemetry,
        @Valid @NotNull Simulator simulator,
        @Valid @NotNull Demo demo,
        @Valid @NotNull Vision vision,
        @Valid @NotNull Risk risk,
        @Valid @NotNull @DefaultValue Predictions predictions,
        @Valid @NotNull @DefaultValue Agent agent,
        @Valid @NotNull @DefaultValue Assistant assistant,
        @Valid @NotNull @DefaultValue RateLimit rateLimit) {

    public record Security(String edgeApiKey, String agentApiKey, @DefaultValue List<User> users) {
    }

    public record User(String username, String password, @DefaultValue List<String> roles) {
    }

    /** Per-user limits for expensive AI endpoints (requests per minute, burst up to the same number). */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @Min(1) @DefaultValue("10") int investigationsPerMinute,
            @Min(1) @DefaultValue("20") int inspectionsPerMinute,
            @Min(1) @DefaultValue("60") int predictionsPerMinute,
            @Min(1) @DefaultValue("60") int searchesPerMinute,
            @Min(1) @DefaultValue("5") int assistantMessagesPerMinute) {
    }

    public record Telemetry(
            @DefaultValue("7d") Duration retention,
            @DefaultValue("90d") Duration rollupRetention,
            @DefaultValue("2h") Duration analysisWindow,
            @DefaultValue("4.0") double zscoreThreshold,
            @DefaultValue("60") int baselineSize) {
    }

    public record Simulator(@DefaultValue("true") boolean enabled, @DefaultValue("10s") Duration interval) {
    }

    public record Demo(@DefaultValue("true") boolean seed, @DefaultValue("3h") Duration history) {
    }

    public record Vision(
            @NotNull String modelPath,
            @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.5") double defectThreshold) {
    }

    /** Limits for the Kiro agent's MCP tool calls. */
    public record Agent(
            @DefaultValue("true") boolean recordToolsEnabled,
            @DefaultValue("10s") Duration readToolTimeout,
            @DefaultValue("30s") Duration recordToolTimeout,
            @DefaultValue("8000") int maxArgumentChars,
            @DefaultValue("60") int callsPerMinute) {
    }

    /**
     * The Kiro assistant page. Each engineer's message runs {@code <command> chat --agent <agent>} as a child process
     * in {@code workspace}, which must contain {@code .kiro/agents/<agent>.json}. The run uses the Kiro login of the
     * operating-system user that runs the app, and its credits. {@code workspace} is a plain string, resolved against
     * the app's working directory: bound as a Path, "." would resolve to the servlet container's document root.
     *
     * @param timeout            a run that takes longer is stopped
     * @param maxConcurrentRuns  across all engineers; each run starts its own Kiro processes
     */
    public record Assistant(
            @DefaultValue("false") boolean enabled,
            @NotBlank @DefaultValue("kiro-cli") String command,
            @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}") @DefaultValue("bas-web-assistant") String agent,
            @NotBlank @DefaultValue(".") String workspace,
            @DurationMin(seconds = 1) @DurationMax(minutes = 30) @DefaultValue("5m") Duration timeout,
            @Min(1) @Max(8) @DefaultValue("2") int maxConcurrentRuns,
            @Min(100) @Max(8000) @DefaultValue("2000") int maxMessageChars) {
    }

    public record Risk(@DefaultValue("4000") int trainingSamples, @DefaultValue("42") long randomSeed,
            @DefaultValue("15m") Duration predictionInterval) {
    }

    /**
     * How long stored predictions are kept and how many rows each table may hold. Age limits are applied hourly;
     * past a row limit the oldest rows go first. Failure-risk rows that an investigation or a confirmed failure
     * refers to are never removed. The row limits are at least 1,000, the largest single ML-lab call, so a call
     * never removes its own rows.
     *
     * @param retention  {@code model_predictions}
     * @param maxRows    {@code model_predictions}, checked hourly (its writes are one row per call)
     * @param mlRetention {@code ml_predictions}
     * @param mlMaxRows  {@code ml_predictions}, checked after every write (one call can write 1,000 rows)
     */
    public record Predictions(
            @DurationMin(days = 1) @DefaultValue("90d") Duration retention,
            @Min(1000) @DefaultValue("200000") int maxRows,
            @DurationMin(days = 1) @DefaultValue("30d") Duration mlRetention,
            @Min(1000) @DefaultValue("200000") int mlMaxRows) {
    }
}

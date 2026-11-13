package com.basintelligence.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

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
        @Valid @NotNull @DefaultValue Agent agent,
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
            @Min(1) @DefaultValue("60") int searchesPerMinute) {
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

    public record Risk(@DefaultValue("4000") int trainingSamples, @DefaultValue("42") long randomSeed,
            @DefaultValue("15m") Duration predictionInterval) {
    }
}

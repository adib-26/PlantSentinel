package com.basintelligence.web.api;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.basintelligence.ml.MlPredictionService;
import com.basintelligence.ml.ModelCard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Prediction API for the models trained by the ML lab (step 10: Java inference). */
@RestController
@RequestMapping("/api/ml")
@Validated
@Tag(name = "Machine learning models")
public class MlApiController {

    public record ProcessRequest(
            @NotNull @Pattern(regexp = "[LMHlmh]") String type,
            @NotNull @DecimalMin("250") @DecimalMax("350") Double airTempK,
            @NotNull @DecimalMin("250") @DecimalMax("400") Double processTempK,
            @NotNull @DecimalMin("0") @DecimalMax("5000") Double rpm,
            @NotNull @DecimalMin("0") @DecimalMax("200") Double torqueNm,
            @NotNull @DecimalMin("0") @DecimalMax("1000") Double toolWearMin) {
    }

    public record RulRequest(@NotEmpty @Size(max = 500) List<@NotNull @Size(min = 21, max = 21) List<Double>> cycles) {
    }

    public record VibrationRequest(@NotNull @Size(min = 1024, max = 1024 * 64) List<Double> samples) {
    }

    private final MlPredictionService ml;

    public MlApiController(MlPredictionService ml) {
        this.ml = ml;
    }

    @GetMapping("/models")
    @Operation(summary = "Installed models with their model cards (version, dataset, features, metrics, checksum)")
    public List<ModelCard> models() {
        return ml.models();
    }

    @PostMapping("/process-failure")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.PREDICTION)
    @Operation(summary = "Batch: probability that a machining process fails (AI4I 2020 model)")
    public List<MlPredictionService.ProcessPrediction> processFailure(
            @RequestBody @NotEmpty @Size(max = 1000) List<@Valid ProcessRequest> batch) {
        return ml.predictProcessFailure(batch.stream().map(r -> new MlPredictionService.ProcessInput(r.type().charAt(0),
                r.airTempK(), r.processTempK(), r.rpm(), r.torqueNm(), r.toolWearMin())).toList());
    }

    @PostMapping("/remaining-useful-life")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.PREDICTION)
    @Operation(summary = "Remaining useful life in cycles from a C-MAPSS-style sensor history (21 sensors per cycle)")
    public MlPredictionService.RulPrediction remainingUsefulLife(@RequestBody @Valid RulRequest request) {
        return ml.predictRemainingUsefulLife(request.cycles().stream()
                .map(c -> c.stream().mapToDouble(Double::doubleValue).toArray()).toList());
    }

    @PostMapping("/bearing-vibration")
    @com.basintelligence.web.RateLimited(com.basintelligence.web.RateLimited.Kind.PREDICTION)
    @Operation(summary = "Anomaly score and likely fault per 1024-sample window of 12 kHz accelerometer data (g)")
    public List<MlPredictionService.BearingWindowResult> bearingVibration(@RequestBody @Valid VibrationRequest request) {
        return ml.analyzeBearingVibration(request.samples().stream().mapToDouble(Double::doubleValue).toArray());
    }
}

# BAS Intelligence ML lab

Offline training and evaluation in **Java with Smile**, run on the laptop CPU. The application serves the resulting
models through `/api/ml/*` and Kiro's MCP tools. One command reproduces everything:

```bash
ml/run-ml-lab.sh        # downloads datasets if needed, then trains, evaluates and exports (about 20 s, ~400 MB RAM)
```

## Development sequence and evidence

| Step | Work | Evidence |
|---|---|---|
| 1 | Exploratory data analysis | [`reports/01-ai4i-data-quality.md`](reports/01-ai4i-data-quality.md), data-quality sections of reports 03 and 04 |
| 2 | Feature engineering | `ProcessFailureFeatures`, `RulFeatures`, `BearingFeatures` in `src/main/java/com/basintelligence/ml/`, and the `features` list in every model card |
| 3 | Baseline logistic regression | Report 02 (first row) |
| 4 | Decision tree and random forest | Report 02 |
| 5 | Gradient boosting | Report 02 (selected model) |
| 6 | Anomaly detection | [`reports/04-cwru-bearing-anomaly-case-study.md`](reports/04-cwru-bearing-anomaly-case-study.md) |
| 7 | RUL prediction | [`reports/03-cmapss-rul-evaluation.md`](reports/03-cmapss-rul-evaluation.md) |
| 8 | Model selection | [`reports/00-model-evaluation-summary.md`](reports/00-model-evaluation-summary.md) |
| 9 | Export model | `src/main/resources/models/<name>-v<version>.ser` plus `<name>.card.json` |
| 10 | Java inference | `MlPredictionService`, `POST /api/ml/process-failure`, `/remaining-useful-life`, `/bearing-vibration`; MCP tools `predict_process_failure`, `list_ml_models` |

Vision: `MvtecVisionEvaluation` scores the deployed ONNX defect model on MVTec AD (report 05) once you download a
category to `ml/data/raw/mvtec/<category>/`. MVTec needs a licence form, so it is not downloaded automatically.

## Results (seed 42)

| Model | Dataset | Selected algorithm | Test result |
|---|---|---|---|
| Process failure classifier | AI4I 2020 | Gradient boosting | PR-AUC 0.904, ROC-AUC 0.990, F1 0.860, recall 0.765, false alarm rate 0.1% |
| Remaining useful life | C-MAPSS FD001 | Random forest | RMSE 18.7 cycles, MAE 13.7, R² 0.80 (official test set) |
| Bearing anomaly detector | CWRU, unseen 3 hp load | Isolation Forest | Recall 1.00, false alarm rate 4.4% per window, 0% with a 3-window persistence rule, 256 ms delay |
| Bearing fault classifier | CWRU, unseen 3 hp load | Random forest | Accuracy 1.00 (seeded faults on one rig; see caveats) |

Honest reading of the results:
- The logistic baseline reaches ROC-AUC 0.90 but only PR-AUC 0.40. The failure modes in AI4I are threshold
  interactions, which trees capture and a linear model cannot.
- The RUL model is weaker than published deep-learning results (RMSE 12 to 16) and is a reasonable classical baseline.
- The bearing detector's false alarm rate rises from the 1% target to 4.4% on a load it was not calibrated on.
  Calibrate per machine and load, or require persistence before alarming.
- CWRU faults are seeded with EDM on a single test rig, so perfect classification here does not mean it will work
  on plant bearings.

## Metrics used

- Classification: precision, recall, F1, confusion matrix, ROC-AUC (Mann-Whitney, ties averaged) and PR-AUC
  (average precision). Thresholds are tuned for F1 on validation only.
- Regression: MAE, RMSE, R², plus the NASA asymmetric score for RUL (late predictions cost more).
- Anomaly detection: precision and recall on labelled test windows, false alarm rate (FP / (FP + TN)), and detection
  delay from fault onset under two alarm rules.

Definitions live in `src/main/java/com/basintelligence/mllab/Metrics.java` and are unit tested in `MetricsTest`.

## Model governance

Every exported model has a card (`src/main/resources/models/*.card.json`) recording: model version, training
dataset name, licence and version (SHA-256 of the raw files from `ml/data/raw/SHA256SUMS`), training date, feature
list with definitions, split strategy, decision threshold, validation and test metrics, limitations, and the artifact
SHA-256. At startup `ModelRegistry` refuses any artifact whose checksum does not match its card, and deserialises only
an allow-list of classes (`com.basintelligence.ml.*`, `smile.*`, core JDK types). See `/models` in the web UI.

Splits are chosen to avoid leakage. AI4I uses a stratified random split. C-MAPSS splits by engine, so no engine appears
in both training and validation. CWRU splits by motor load, so the test load is never seen in training.

## Running on 8 GB RAM

- Small, public datasets (73 MB raw in total). Signals are converted to windows of features as they load, and only the
  features are kept in memory.
- The lab runs with `-Xmx1536m` and peaks at about 400 MB resident. Stop the app (or the IDE's extra run
  configurations) while it runs.
- No deep-learning training. Vision uses a compact ONNX model (744 bytes) with images scored one at a time.
- Inference endpoints accept batches (up to 1000 process rows, or 64 vibration windows per call).
- ONNX Runtime note: the `onnxruntime` 1.31.1 Java jar used here bundles native libraries for `win-x64`,
  `linux-x64`, `linux-aarch64` and `osx-aarch64` (checked by listing the jar). It runs natively on Apple Silicon, but
  this version has no Intel-Mac (`osx-x64`) build, so on an Intel Mac pin an older onnxruntime that still ships one.

## Datasets and licences

See [`datasets/README.md`](datasets/README.md). Raw data is never committed. Trained model artifacts are derived works:
the AI4I model is shared under CC BY 4.0 attribution, and MVTec AD results must stay non-commercial.

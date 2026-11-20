# Monitoring

Monitoring uses Spring Boot Actuator, Micrometer and structured logs. Everything stays inside the application, with
no extra processes, so it fits an 8 GB laptop. Prometheus and Grafana can be added later without code changes.

## Where to look

| What | How |
|---|---|
| All nine signals in one document | `GET /actuator/monitoring` (ADMIN, signed in to the web UI) |
| A single meter, filterable by tag | `GET /actuator/metrics/{name}?tag=key:value`, e.g. `/actuator/metrics/bas.telemetry.invalid?tag=reason:MISSING_VALUE` |
| Liveness and readiness | `GET /actuator/health` (public), `/actuator/health/liveness`, `/actuator/health/readiness` |
| Structured logs | `data/logs/bas-intelligence.json`: one ECS JSON object per line, with `requestId` and `user`; rotated at 10 MB, 100 MB cap |

`/actuator/**` sits behind the web login, so use a browser session as admin; HTTP Basic does not work there.
Every response carries `X-Request-Id`, which matches `requestId` in the logs. A well-formed id sent by the client
(8–64 characters of `A-Za-z0-9._:-`) is kept, so an edge node can correlate its own logs with ours.

## The signals

| Metric | Meters | Recorded in | Notes |
|---|---|---|---|
| API response time | `http.server.requests` (tags uri, method, status, outcome) | Spring Boot | p50/p95/p99 in-process; SLO buckets 50 ms to 5 s; `/actuator/monitoring` lists the five slowest endpoints |
| Telemetry ingestion rate | `bas.telemetry.ingestion.rate` (gauge, readings in the last 60 s), `bas.telemetry.readings{source}`, `bas.telemetry.ingest.duration{source}`, `bas.edge.batch.size` | `TelemetryService`, `EdgeTelemetryController` | the gauge gives a rate without Prometheus |
| Invalid telemetry count | `bas.telemetry.invalid{reason}`: `MISSING_VALUE`, `IMPOSSIBLE_VALUE`, `STUCK_VALUE`, `INVALID_TIMESTAMP`, `UNKNOWN_MACHINE`, `DUPLICATE`, `MALFORMED_BATCH` | `SensorFaultService`, `TelemetryService`, `EdgeTelemetryController`, `ApiExceptionHandler` | sensor faults count once per faulty channel |
| ML inference latency | `bas.inference{model}`: `failure-risk`, `defect-detector`, `process-failure-classifier`, `remaining-useful-life-regressor`, `bearing-anomaly-detector` | `RiskService`, `InspectionService`, `MlPredictionService` | p50/p95/p99 |
| Prediction distribution | `bas.predictions{model,outcome}` (risk level, failure or not, anomalous or not, bearing condition, PASS/DEFECT, RUL band); `bas.prediction.probability{model}` with buckets every 0.1; `bas.prediction.rul.cycles` | `PredictionService`, `InspectionService`, `MlPredictionService` | risk predictions are counted when stored (scheduled, anomaly-triggered, investigation), not on every page view |
| Stored predictions deleted | `bas.predictions.removed{table,reason}`: `model_predictions` / `ml_predictions`, `retention` / `row_limit` | `PredictionService`, `MlPredictionLog` | any `row_limit` count means rows went before their retention period ended; see [Retention and row limits](#retention-and-row-limits) |
| Kiro request latency | `bas.agent.tool.duration{tool,outcome}`; `http.server.requests{uri=/mcp}` | `AuditingToolCallback` | SLO buckets 100 ms to 30 s |
| Kiro assistant runs | `bas.assistant.messages{outcome}` (DONE, FAILED, TIMED_OUT, CANCELLED); `bas.assistant.duration`; `bas.assistant.credits` | `KiroAssistantService` | credits are what Kiro CLI reports per run on the server's Kiro login |
| Kiro token usage | `bas.agent.tokens.estimated{direction,tool}` | `AuditingToolCallback` | **estimate** at about 4 characters per token. It covers only what tool arguments (input) and results (output) add to Kiro's context. Kiro's own prompt and completion tokens are billed in Kiro and are not visible to this server. |
| Tool failure count | `bas.agent.tool.calls{tool,outcome}`: `SUCCESS`, `ERROR`, `TIMEOUT`, `DENIED` | `AuditingToolCallback` | failures are everything except SUCCESS; details per call stay in `agent_tool_audit` (Agent activity page) |
| RAG retrieval quality | online: `bas.rag.searches{retriever,outcome=hit/empty}`, `bas.rag.search.duration`, `bas.rag.top.score`, `bas.rag.citations{verified}`; benchmark gauges: `bas.rag.eval.hit.at.1`, `bas.rag.eval.recall.at.3`, `bas.rag.eval.mrr` | `ManualService`, `InvestigationService`, `RetrievalQualityMonitor` | see below |

Meter names are constants in `common/BasMetrics`. Counters are totals since the application started. Micrometer's
in-process percentiles cover roughly the last two minutes.

### Retrieval quality benchmark

`src/main/resources/rag/golden-queries.tsv` holds 22 labelled questions about the bundled manuals. They are phrased
the way an engineer would ask, each with the section that should answer it. `RetrievalQualityMonitor` runs them
through the live retriever and reports:

- **hit@1**: the expected section is the first result.
- **recall@3**: the expected section is in the top 3.
- **MRR**: mean reciprocal rank over the top 5.

The benchmark runs again only after the library changes (upload, delete, index rebuild), the first time someone reads
the gauges, and its searches are not counted as usage. Current result with BM25 on H2: hit@1 0.59, recall@3 0.86,
MRR 0.72. The one miss is "the mill spindle runs far too hot after an hour". `knowledge/RetrievalQualityGateTest`
fails the build if recall@3 falls below 0.80 or MRR below 0.65. Uploaded manuals are searched as usual, but no
question tests them. Add lines to the TSV file to cover them.

`bas.rag.citations{verified=false}` counts evidence labels that Kiro cited in an interpretation but that do not exist
in the investigation. It is the closest online signal of the agent misusing retrieved evidence.

## Cost on the laptop

- In-memory meters, a few hundred series, a few MB of heap. The demo used about 116 MB of heap after startup.
- No extra processes.
- The log file is capped at 100 MB.
- The benchmark is about 20 in-memory BM25 searches, only after library changes.
- On Neon, reading the gauges never wakes the database unless the library changed.

## Adding Prometheus and Grafana later

1. Add `io.micrometer:micrometer-registry-prometheus` to `pom.xml`; the version is managed by Spring Boot. The
   `prometheus` endpoint is already in the exposure list.
2. Scrape `/actuator/prometheus` every 30–60 s. It needs ADMIN, so either give the scraper an admin session or add a
   dedicated key-authenticated chain for it. The SLO and probability buckets are already configured for histograms.
3. Useful queries:
   - ingestion rate: `rate(bas_telemetry_readings_total[5m])`
   - tool failure ratio: `sum(rate(bas_agent_tool_calls_total{outcome!="SUCCESS"}[15m])) / sum(rate(bas_agent_tool_calls_total[15m]))`
   - API p95: `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))`

Prometheus plus Grafana adds roughly 300–500 MB of RAM, so start them only when the laptop has headroom.

## Model governance

Every model prediction is stored: failure risk in `model_predictions` (scheduled, anomaly-triggered,
investigation, agent, or a `GET /api/machines/{code}/risk` call), visual inspection in `inspections`, and the
ML-lab endpoints (`POST /api/ml/*`) in `ml_predictions`. The view `prediction_governance` (migrations V15, V16)
lists all three under the governance field names. Its key is (`source_table`, `prediction_id`). Only the dashboard
(including its 30-second refresh, `GET /overview.json`, and the Kiro ghost's `GET /machines.json`), machine and
analytics pages compute risk without storing it, because every page load or refresh would add a row per machine.

| Field | Failure risk (`model_predictions`) | Visual inspection (`inspections`) |
|---|---|---|
| `source_table` | `model_predictions` | `inspections` |
| `prediction_id` | `id` | `id` |
| `device_id` | the machine | the machine |
| `prediction_timestamp` | `predicted_at`, when the model ran (UTC) | `inspected_at` |
| `model_name` | `failure-risk` | `defect-detector` |
| `model_version` | recipe version + first 12 hex of the SHA-256 of the fitted coefficients, e.g. `1.0+w3f9a0c12b7de`. The model is retrained at startup from a seed, so the same seed and sample count give the same version; any change to the weights changes it. Bump `FailureRiskModel.RECIPE_VERSION` when the features or training change. | model file name + first 12 hex of its SHA-256, e.g. `defect-detector.onnx@82635f870d25` |
| `predicted_class` | LOW / MEDIUM / HIGH / CRITICAL, from thresholds 0.25 / 0.5 / 0.75 on the score | PASS / DEFECT |
| `risk_score` | model probability, 0–1 | defect probability, 0–1 |
| `predicted_value` | NULL | NULL |
| `input_reference` | `telemetry[<from>/<to>] n=<readings> features=sha256:<hex>` | `image:<stored file> sha256:<hex of the uploaded bytes>` |
| `trigger_type` | SCHEDULED / ANOMALY / INVESTIGATION / AGENT / API | INSPECTION |

Example: `SELECT * FROM prediction_governance WHERE device_id = ? ORDER BY prediction_timestamp DESC`.

### ML-lab predictions

The ML-lab endpoints score data that the caller sends. Add `?machine=<code>` to link the stored rows to a machine
(404 if the caller cannot see it); without it, `device_id` is NULL. Each response item carries the id of its stored
row: `predictionId` for process failure and RUL, `anomalyPredictionId` and `conditionPredictionId` per bearing
window. `model_version` is the card version + first 12 hex of the artifact SHA-256, e.g. `1.0.0+355bad0b9685`; the
registry only serves an artifact whose checksum matches its card. The responses' `modelVersion` uses the same form.
`trigger_type` is `API`, `requested_by` the user, and `request_id` groups the rows of one call.

`risk_score` is always the model's own 0–1 score for the bad outcome, and `predicted_class` follows from it and the
model's threshold. When a model has no such score, `risk_score` is NULL; nothing is derived to fill it.
`predicted_value` holds a regression's output and is NULL for every other model.

| Model | Rows | `predicted_class` | `risk_score` | `input_reference` |
|---|---|---|---|---|
| `process-failure-classifier` | one per batch item | `failure` when `risk_score` ≥ the card threshold, else `no_failure` | failure probability | the input itself: `process[type=M airTempK=298.1 processTempK=308.6 rpm=1551.0 torqueNm=42.8 toolWearMin=0.0]` |
| `remaining-useful-life-regressor` | one per request | `0-30_cycles` / `31-60_cycles` / `over_60_cycles` | NULL: a regression has no probability. The cycles are in `predicted_value`. | `cycles n=<cycles> x 21 sensors sha256:<hex>` |
| `bearing-anomaly-detector` | one per 1024-sample window | `anomalous` when `risk_score` > the card threshold, else `normal` | Isolation Forest anomaly score, 0–1 | `vibration[<from>,<to>) of <n> samples sha256:<hex>` |
| `bearing-fault-classifier` | one per window | the most probable of `normal` / `inner_race` / `ball` / `outer_race` | NULL (see below). The four class probabilities are in `ml_predictions.probabilities_json`, in that order. | same as the detector row of that window |

The fault classifier has no risk score because the ML lab evaluated only its most-probable-class decision. It chose
no fault threshold on validation data and did not check calibration, so a derived number such as 1 − P(`normal`)
would be an unvalidated score. That number can also contradict the class: with P(`normal`) = 0.45 and the rest
spread over three faults, the class is `normal` but 1 − P(`normal`) is 0.55. Whether a window is abnormal is the
detector's job; its row for the same window (same `request_id` and `item_index`) has a validated threshold.

The digests are SHA-256 over the values as 8-byte big-endian IEEE 754 doubles in request order (for RUL, cycle by
cycle, s1..s21 within a cycle), so a caller that kept its input can confirm which request a row came from. The
classes are the same labels as the `bas.predictions` metric outcomes.

### Retention and row limits

Stored predictions are deleted by two hourly jobs, which pause like the other background jobs while nobody uses the
app. A row is deleted when it is older than its table's retention period or, past the table's row limit, when it is
among the oldest rows. Failure-risk predictions that an investigation (`investigations.prediction_id`) or a confirmed
failure (`failure_events.prior_prediction_id`) refers to are never deleted. They are the evidence those records were
based on, and the failure link is how predictions are checked against reality. Deletes run in statements of at most
5,000 ids, and migration V17 indexes the columns they search.

| Setting | Table | Default | Neon profile |
|---|---|---|---|
| `bas.predictions.retention` | `model_predictions` | 90 days | 90 days |
| `bas.predictions.max-rows` | `model_predictions`, checked hourly | 200,000 | 100,000 |
| `bas.predictions.ml-retention` | `ml_predictions` | 30 days | 30 days |
| `bas.predictions.ml-max-rows` | `ml_predictions`, checked after every write and hourly | 200,000 | 50,000 |

The age limit alone does not bound storage. One process-failure call writes up to 1,000 rows, and the prediction
rate limit allows 60 calls per minute per user, so one user could add about 60,000 rows (about 30 MB) per minute.
The row limits bound the worst case instead. On PostgreSQL 17 a failure-risk row takes about 1.8 KB and an ML-lab
row about 0.5 KB, indexes included. That puts the Neon limits at about 0.2 GB of the free plan's 1 GB per project,
and the defaults at about 0.46 GB. Because `ml_predictions` is checked after every write, it never holds more than its
limit, except briefly while concurrent calls are still writing. Failure-risk writes are one row per call, so an hourly
check is enough. The row limits must be at least 1,000, the largest single call, so a call never deletes its own
rows.

At the row limit, rows are deleted before their retention period ends. Each deletion is counted in
`bas.predictions.removed{table, reason}`, where `reason` is `retention` or `row_limit`. Any `row_limit` count means
the limit was reached, and the log also gets a warning (for `ml_predictions`, at most one per hour). Raise the limit
if storage allows. Once a prediction is deleted, its verification link answers 404.

### The input outlives the raw telemetry

Raw telemetry is pruned after `bas.telemetry.retention` (7 days, 3 on Neon). So each failure-risk row also stores
its input in `input_json`: the window, the reading count, the mean of each channel and the feature digest
(about 300 bytes). The feature values themselves are in `contributions_json`. Inspection images are kept on disk
without a retention limit.

### Checking a stored prediction

`GET /api/machines/{code}/predictions` lists the stored failure-risk predictions under the governance names in
camelCase: `predictionId`, `deviceId`, `predictionTimestamp`, `modelName`, `modelVersion`, `predictedClass`,
`riskScore`, `inputReference` (plus `trigger`, `anomalyEventId` and `readingsUsed`). On the analytics page, the
*check* link next to each prediction, or `GET /api/machines/{code}/predictions/{id}/verification`, runs these checks:

| Check | Passes when |
|---|---|
| risk class | `predicted_class` follows from `risk_score` |
| input record | `input_json`, `input_reference` and `readings_used` agree |
| feature digest | the feature values in `contributions_json` hash to the digest in `input_reference` and `input_json` |
| feature engineering | today's feature code and the machine type's nominal values turn the recorded channel means into the same features |
| model re-score | the running model has the same version and gives the same score. Skipped when the version differs. |
| raw telemetry | the readings in the window are still stored and reproduce the features. Skipped once retention has pruned them; fails if readings inside the retention period were changed, removed or added. |

The result is `VERIFIED` (nothing failed), `MISMATCH` (something failed) or `UNVERIFIABLE`. The last applies to
rows written before V15, which have `model_version = 'unrecorded'`, no window and no `input_json`. Inspections
stored before V15 have no image digest. A passing check shows that the record is intact and reproducible. It
does not show that the estimate was right: the risk model is trained on simulated fault data. The verification
endpoint covers failure-risk predictions only; ML-lab rows are checked by recomputing the digest or re-scoring the
recorded process input.

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
| Prediction distribution | `bas.predictions{model,outcome}` (risk level, failure or not, anomalous or not, PASS/DEFECT, RUL band); `bas.prediction.probability{model}` with buckets every 0.1; `bas.prediction.rul.cycles` | `PredictionService`, `InspectionService`, `MlPredictionService` | risk predictions are counted when stored (scheduled, anomaly-triggered, investigation), not on every page view |
| Kiro request latency | `bas.agent.tool.duration{tool,outcome}`; `http.server.requests{uri=/mcp}` | `AuditingToolCallback` | SLO buckets 100 ms to 30 s |
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

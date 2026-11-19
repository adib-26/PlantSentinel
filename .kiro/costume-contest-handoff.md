# Costume Contest UI: where the work stopped (2026-11-18)

Read this when the user says "continue". Delete the file when the work is finished.

## Request
Haunted, polished, highly interactive UI for BAS Intelligence, where every spooky element maps to real data.
Daylight (the original look) stays available through the costume toggle. Keep function, data, accessibility
(reduced motion, contrast, keyboard, print, mobile) and security (textContent only, scoped data, rate limits) intact.
Do not overwrite the user's own uncommitted changes.

The final report must contain these lines:
- `[STEERING steer-b84b6ef1-fe38-46d9-b193-550df2f00426: <what was done to make it interactive>]`
- `[STEERING steer-5db568e8-0698-437b-ab03-aba7fdcbc5a1: paused on request, wrote this handoff, resumed on "continue">]`

## Done and checked with screenshots
- New files: `static/css/haunted.css`, `static/js/costume-init.js`, `haunted.js`, `spirit-board.js`, `haunted-pages.js`.
- Templates changed: fragments, login, error, dashboard, machine-detail, investigation, maintenance, assistant,
  manuals, reports. Chart scripts: machine-charts.js, analytics-charts.js.
- `GET /machines.json` in `MachineWebController`: scoped by `machines.list()`, `@RateLimited(PREDICTION)`, risk from
  `risk.assess`. `RiskService.assess` writes nothing to the database (it only times the inference in Micrometer),
  so polling it does not add prediction rows.
- The live overview updates every 30 s: card risk, aura and meter, EMF needle, LEDs, mean and highest risk.

## In progress: make every overview figure live
`/tmp/haunt-shots/live.png` showed that after a live risk change these figures stay stale: Need attention, Operating
normally, the night-watch sentence, the risk-level bars (MEDIUM 1 / CRITICAL 1 instead of 0 / 2), "N machines
assessed", active alerts and the alert list, health and connectivity badges, anomaly counts, the data-quality line,
and the gone-quiet block. The Kiro ghost bubble also briefly covers the live note.

Planned fix (nothing written yet; `DashboardController.java` has no uncommitted changes, so it is safe to edit):
1. In `DashboardController`, move the card, alert and summary computation of `dashboard()` into one private method.
   Use it for the page and for a new `GET /overview.json`, annotated `@RateLimited(PREDICTION)` and scoped the same way.
   Return the KPI counts, byLevel, assessed, mean and highest, pendingReviews, alerts (id, code, severity, message,
   ago), and per machine the `/machines.json` fields plus connectivity, anomalies24h, attention and
   server-formatted text (pct, quality line, tones). Put the night-watch sentence in a small `NightWatch` record
   so the template and the JSON share one text.
2. In `dashboard.html`, add `data-live` hooks. Wrap the badge fragments, which `th:replace` removes. Render the
   risk/no-data blocks, the apparition icon and the gone-quiet block always, and toggle them with `hidden`.
3. In `haunted-pages.js`, poll `/overview.json` on the overview and apply every figure. Replace the "Reload for every
   figure" note with a timestamped change log (`role="log"`) covering risk, health and connectivity changes and new
   critical alerts. In `spirit-board.js`, expose `H.feedMachines(list, at)` and skip its own polling on the overview.
   Stop polling when a response is redirected (expired session).

## Still to do after that
- Screenshots: live update again, mobile (`--mobile`), `--reduced`, the worried ghost, the board in daylight.
- Run `./mvnw test`. `DashboardScreensIntegrationTest` asserts on dashboard HTML and may need updating.
- Stop the scratch instance, then write the final report: features and what each one means, how to try them
  (`/`, Ctrl/Cmd+K, type "boo", costume toggle, sound, lantern on the sign-in page), known limits, steering lines.

## Scratch setup (outside the repo)
- `/tmp/bas-haunt` is an rsync copy of the project. `/tmp/bas-haunt-res` links to the real `src/main/resources`, so
  template and static edits apply without a rebuild. Java changes need a rebuild. If a reboot cleared `/tmp`, run
  `rsync -a --exclude target --exclude data --exclude .git --exclude ml/data --exclude ml/.venv --exclude node_modules ./ /tmp/bas-haunt/`
  and `ln -s "$PWD/src/main/resources" /tmp/bas-haunt-res`.
- Build: `export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.13.0.3/libexec/openjdk.jdk/Contents/Home; ./mvnw -q -o -DskipTests package`
- Run (background), from `/tmp/bas-haunt`:
  `java -Xmx768m -jar target/bas-intelligence-0.1.0-SNAPSHOT.jar --server.port=8093 "--spring.datasource.url=jdbc:h2:mem:haunt;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1" --bas.data-dir=/tmp/bas-haunt-data --spring.thymeleaf.prefix=file:/tmp/bas-haunt-res/templates/ --spring.thymeleaf.cache=false --spring.web.resources.static-locations=file:/tmp/bas-haunt-res/static/ > /tmp/bas-haunt-run.log 2>&1`
- Stop: `pkill -f "bas-intelligence-0.1.0-SNAPSHOT.jar --server.port=8093"`
- Screenshots: `node /tmp/haunt-shots/shoot.mjs [--full] [--wait ms] [--after ms] [--costume haunted|daylight] [--reduced] [--mobile] [--nologin] [--user u --pass p] name=/path[@@js]`
  writes `/tmp/haunt-shots/<name>.png` (headless Chrome, Node 22). Users: engineer/engineer123, viewer/viewer123,
  reviewer/reviewer123.

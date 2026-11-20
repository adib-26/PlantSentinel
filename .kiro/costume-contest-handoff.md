# Costume Contest UI: where the work stopped (2026-11-19)

Read this when the user says "continue". Delete the file when the work is finished.

## Request
Haunted, polished, highly interactive UI for BAS Intelligence, where every spooky element maps to real data.
Daylight (the original look) stays available through the costume toggle. Keep function, data, accessibility
(reduced motion, contrast, keyboard, print, mobile) and security (textContent only, scoped data, rate limits) intact.
Do not overwrite the user's own uncommitted changes.

The final report must contain these lines:
- `[STEERING steer-b84b6ef1-fe38-46d9-b193-550df2f00426: <what was done to make it interactive>]`
- `[STEERING steer-5db568e8-0698-437b-ab03-aba7fdcbc5a1: paused on request, wrote this handoff, resumed on "continue">]`
- `[STEERING steer-fd9bacef-e364-4522-b617-0e6fb1192238: paused again on request, updated this handoff, resumed on "continue">]`

## Done earlier (committed in 65e5881)
Costume files (`haunted.css`, `costume-init.js`, `haunted.js`, `spirit-board.js`, `haunted-pages.js`), the changed
templates, and `GET /machines.json`.

## Done on 2026-11-19 (not committed; nothing else in the tree was changed)
Every overview figure is now live:
- `DashboardController`: `overview()` computes cards, alerts and summary once, for the page and for the new
  `GET /overview.json` (`@RateLimited(PREDICTION)`, scoped by `machines.list()`). `NightWatch` record gives one
  narrator text (now also covers machines that have gone quiet). `MachineCard` helpers: `level()`, `health()`,
  `connectivity()`, `restless()`, `possessed()`, `quiet()`, `quietText()` (NO_DATA has its own text),
  `anomaliesText()`. `RiskSummary.assessedText()` / `highestText()`.
- New `web/ui/LiveOverview.java`: the JSON (counts, risk summary, 10 alerts with keys, machine rows with the
  `/machines.json` fields plus server-formatted texts and tones). `Formatters.dataLine(...)` shared with the template.
- `dashboard.html`: baseline JSON in `<main data-overview>`; `data-live` hooks on every figure; badge wrappers;
  risk, no-data, apparition and gone-quiet blocks always rendered and toggled with `hidden`; change log
  (`role="log"`, `data-ghost-avoid`) under the machine cards, in the wide left column.
- `haunted-pages.js` `liveOverview()`: polls every 30 s, applies every figure, KPI change flash, timestamped change log
  (risk, health, connectivity, new critical alerts, new machines, session ended), Retry-After back-off on 429/503,
  20 s timeout, pauses while hidden, stops on redirect (session ended). The ghost narrates only when the log is out
  of view.
- `spirit-board.js`: `H.feedMachines(list)`; no own polling on the overview; `redirect: 'manual'` and stop polling
  when the session has ended; unprompted ghost lines never cover `[data-ghost-avoid]`; "turned CRITICAL" names only
  the machines that newly turned critical.
- `haunted.css`: change log in both costumes, stale stamp, NO_DATA cobwebs (cards and machine page), reduced motion
  now also applies in daylight; `.live-note` removed.
- README (screen 1 row) and `docs/monitoring.md` (risk computed without storing) mention the 30 s refresh.

Checked: `./mvnw test` 218 tests, 0 failures (run before the README and monitoring doc edits only).
`/overview.json`: anonymous 302 to /login, viewer sees 4 machines, technician 0 (scoped), 61st call in a minute
429 with Retry-After. Screenshots in `/tmp/haunt-shots`: overview, live, live-full, mobile (+ mobile-log),
daylight-board, reduced, reduced-daylight, worried, ended (+ ended-crop), machine, board. JS passes `node --check`.

## Still to do
1. Review `git diff` once more.
2. Optional: print preview of the overview; the ghost's 60 s polling on a non-overview page in a fresh tab (the
   overview's 30 s cache hides it right after sign-in).
3. Delete this file and write the final report: features and what each one means, how to try them (`/`, Ctrl/Cmd+K
   or `/`, type "boo", costume toggle, sound, lantern on the sign-in page, *Simulate a machine incident* to watch the
   overview change), known limits (below), the steering lines.

Known limits to report (all existed before; none changed):
- Soon after startup the cards show low data completeness (32 to 40%): seeded history has one reading per minute
  (`StartupInitializer.HISTORY_STEP`) while the simulator sends every 10 s, so `DataQuality` counts the one-minute
  gaps as missing readings. It settles after an hour.
- Polling counts as activity (`ActivityTracker`), so a visible overview tab keeps background jobs and Neon awake, as
  the machine page's 15 s chart refresh already does.
- In daylight `/favicon.ico` is a 404 (the costume adds a data: favicon).
- README says 141 tests; there are 218.
- No automated test for `/overview.json` (not requested); scoping and rate limit were checked by hand. Offer one.

## Scratch setup (outside the repo; the app is stopped)
- `/tmp/bas-haunt` is an rsync copy of the project. `/tmp/bas-haunt-res` links to the real `src/main/resources`, so
  template and static edits apply without a rebuild. Java changes need a fresh rsync and rebuild. If a reboot cleared
  `/tmp`, run
  `rsync -a --delete --exclude target --exclude data --exclude .git --exclude ml/data --exclude ml/.venv --exclude node_modules ./ /tmp/bas-haunt/`
  and `ln -s "$PWD/src/main/resources" /tmp/bas-haunt-res`.
- Build, from `/tmp/bas-haunt`: `export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.13.0.3/libexec/openjdk.jdk/Contents/Home; ./mvnw -q -o -DskipTests package`
- Run (background), from `/tmp/bas-haunt`:
  `java -Xmx768m -jar target/bas-intelligence-0.1.0-SNAPSHOT.jar --server.port=8093 "--spring.datasource.url=jdbc:h2:mem:haunt;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1" --bas.data-dir=/tmp/bas-haunt-data --spring.thymeleaf.prefix=file:/tmp/bas-haunt-res/templates/ --spring.thymeleaf.cache=false --spring.web.resources.static-locations=file:/tmp/bas-haunt-res/static/ > /tmp/bas-haunt-run.log 2>&1`
- Stop: `pkill -f "bas-intelligence-0.1.0-SNAPSHOT.jar --server.port=8093"` (shutdown takes a few seconds).
- Screenshots: `node /tmp/haunt-shots/shoot.mjs [--full] [--wait ms] [--after ms] [--costume haunted|daylight] [--reduced] [--mobile] [--nologin] [--user u --pass p] name=/path[@@js]`
  writes `/tmp/haunt-shots/<name>.png` and prints page errors. It drives headless Chrome over the DevTools protocol
  with Node 22's built-in WebSocket (no npm packages); rewrite it if `/tmp` was cleared. The `@@js` part runs in the
  page and may be async; to force a live change, POST `/machines/{code}/incidents` from the page with the
  `_csrf` value of the sign-out form (`fault`, `rampMinutes=1`), wait about 95 s, then read `.live-log-list li`.
  Users: engineer/engineer123, viewer/viewer123, reviewer/reviewer123, technician/technician123.

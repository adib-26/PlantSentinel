---
inclusion: fileMatch
fileMatchPattern: ['src/main/resources/templates/**', 'src/main/resources/static/**']
---
# The haunted costume (UI)

The UI has two costumes: haunted (the default) and daylight (the original Bootstrap look). `costume-init.js` sets
`data-costume` on `<html>` before the first paint, and the switch saves the choice in `localStorage` (`bas.costume`).

## Every spooky effect shows real data
Do not add an effect unless it shows a value the page already states in text. The current mapping is listed at the top of
`static/css/haunted.css`, in the README and in the overview's "How to read the haunting" key. Keep all three in step:
- card aura and risk meter: failure-risk level and probability (`risk-<LEVEL>`, `--risk` 0 to 100)
- flicker and a peeking ghost (`is-restless`): health WARNING or CRITICAL, or risk HIGH or above; trembling (`is-possessed`): CRITICAL
- cobwebs and fading (`conn-STALE`, `conn-OFFLINE`, `conn-NO_DATA`): readings late, stopped, or none in the last hour
- ghost icon: anomalies; EMF needle: mean risk, one light per machine; candle line: the `NightWatch` mood
- evidence: stone measured, etched rule-based, mist model estimate, parchment documented, ghost AI interpretation (not verified)
- poltergeist, apparition, omen, grave: sensor fault, anomaly, prediction, confirmed failure; wax seal: approved by a second engineer

Java decides these states (`DashboardController.MachineCard`, `NightWatch`). Templates and scripts show the result
and never recompute it.

## Scope
- Put costume styles under `[data-costume="haunted"]` and give costume-only markup `class="costume-only"`. Daylight keeps its look.
- Widgets that both costumes use (meters, EMF meter, change log, spirit board) go in section 1 of `haunted.css`.
- Live figures come only from the scoped, rate-limited endpoints `/overview.json` and `/machines.json`. The server
  formats their texts (`LiveOverview`, `Formatters`), so a refreshed figure reads like a freshly loaded one.

## Safety
- No inline `<script>`, `on*=` handlers or `javascript:` links: the CSP blocks them. The `csp-template-check` hook warns.
- Insert page, user and server text with `textContent`. Use `innerHTML` only for fixed markup written in the script.
- Lines the ghost says unprompted must not cover `[data-ghost-avoid]`.

## Accessibility
- `prefers-reduced-motion` stops animation in both costumes, and `prefers-contrast: more` removes the fog. Check both.
- Decoration is `aria-hidden="true"`. Controls are real buttons: switches use `role="switch"` with `aria-checked`, or
  `aria-pressed`. Live text uses `role="log"` or `role="status"`.
- Printing switches to daylight (`beforeprint` in `haunted.js`), and `@media print` hides the rest of the costume.
- Check small screens (up to 767.98 px wide).

## Sound
- Sound is off until the engineer switches it on (`bas.ambience`). It plays only in costume and is never needed to use a feature.
- It is synthesised with Web Audio in `haunted.js`; there are no audio files. Sound functions return whether they
  played, so an effect that goes with a sound only runs when the sound did.
- Measured peaks (0 to 1): chime 0.09, bell 0.11, sighs 0.09, gate creak 0.17. Keep new sounds in that range; only
  the low heartbeat is louder (0.32).

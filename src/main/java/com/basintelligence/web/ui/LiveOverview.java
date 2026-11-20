package com.basintelligence.web.ui;

import java.time.temporal.ChronoUnit;
import java.util.List;

import com.basintelligence.web.ui.DashboardController.MachineCard;
import com.basintelligence.web.ui.DashboardController.NightWatch;
import com.basintelligence.web.ui.DashboardController.Overview;

/**
 * The factory overview as JSON for the live page: the response of {@code GET /overview.json}, and the
 * {@code data-overview} attribute the page is drawn with. Numbers come with the texts and Bootstrap tones the page
 * shows, formatted on the server, so a refreshed figure reads exactly like a freshly loaded one. A machine row has
 * the fields of {@code /machines.json}, so the Kiro ghost and the spirit board can use it as well.
 *
 * @param at the server time of the figures (ISO-8601, milliseconds)
 */
public record LiveOverview(String at, int total, long normal, long attention, int alertCount, long criticalAlerts,
        long pendingReviews, NightWatch nightWatch, Risk risk, List<LiveAlert> alerts, List<LiveMachine> machines) {

    /**
     * @param mean      mean failure risk of the assessed machines, e.g. "23%"
     * @param meanShare the same in whole percent, for the EMF needle
     * @param highest   e.g. "highest CNC-01 71%", null when no machine has been assessed
     */
    public record Risk(int assessed, String assessedText, String mean, long meanShare, String highest,
            List<Level> byLevel) {
    }

    /** @param share bar width: percent of the assessed machines at this level */
    public record Level(String level, long count, long share) {
    }

    /** @param key identifies the alert between refreshes, so new critical alerts can be told apart */
    public record LiveAlert(String key, String code, String severity, String tone, String message, String ago) {
    }

    /**
     * @param risk      failure-risk level, null before the model has assessed the machine
     * @param riskShare failure risk in whole percent (0 to 100), for the aura, the meter and the EMF light
     * @param dataLine  e.g. "data 98% complete · 12s ago"
     * @param quiet     what the card says when readings stop, null while they arrive on time
     */
    public record LiveMachine(String code, String name, String type, String location, String health,
            String lastSeenAt, String risk, Double riskScore, String riskPct, long riskShare, String connectivity,
            int anomalies24h, String anomaliesText, boolean attention, boolean restless, boolean possessed,
            String healthTone, String riskTone, String connectivityTone, String dataLine, String quiet) {
    }

    static LiveOverview of(Overview overview, Formatters fmt) {
        var summary = overview.riskSummary();
        List<Level> levels = summary.byLevel().entrySet().stream()
                .map(e -> new Level(e.getKey().name(), e.getValue(),
                        fmt.share(summary.assessed() == 0 ? 0.0 : (double) e.getValue() / summary.assessed())))
                .toList();
        Risk risk = new Risk(summary.assessed(), summary.assessedText(), fmt.pct(summary.meanProbability()),
                fmt.share(summary.meanProbability()), summary.highestText(), levels);
        List<LiveAlert> alerts = overview.alerts().stream().limit(DashboardController.ALERTS_SHOWN)
                .map(a -> new LiveAlert(a.key(), a.machineCode(), a.event().severity().name(),
                        fmt.tone(a.event().severity()), a.event().message(), fmt.ago(a.event().detectedAt())))
                .toList();
        return new LiveOverview(overview.at().truncatedTo(ChronoUnit.MILLIS).toString(), overview.cards().size(),
                overview.normal(), overview.attention(), overview.alerts().size(), overview.criticalAlerts(),
                overview.pendingReviews(), overview.nightWatch(), risk, alerts,
                overview.cards().stream().map(c -> machine(c, fmt)).toList());
    }

    private static LiveMachine machine(MachineCard card, Formatters fmt) {
        var m = card.machine();
        boolean assessed = card.risk().isPresent();
        return new LiveMachine(m.code(), m.name(), m.type().displayName(), m.location(), card.health(),
                m.lastSeenAt() == null ? null : m.lastSeenAt().toString(),
                assessed ? card.level() : null, assessed ? card.probability() : null,
                assessed ? fmt.pct(card.probability()) : null, fmt.share(card.probability()), card.connectivity(),
                card.anomalies24h(), card.anomaliesText(), card.needsAttention(), card.restless(), card.possessed(),
                fmt.tone(card.health()), fmt.tone(card.level()), fmt.tone(card.connectivity()),
                fmt.dataLine(card.quality().completeness(), m.lastSeenAt()), card.quietText());
    }
}

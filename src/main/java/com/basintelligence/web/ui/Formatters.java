package com.basintelligence.web.ui;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import com.basintelligence.common.Text;

/** Template helpers, used as {@code ${@fmt.time(x)}} in Thymeleaf. */
@Component("fmt")
public class Formatters {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Clock clock;

    public Formatters(Clock clock) {
        this.clock = clock;
    }

    public String time(Instant instant) {
        return instant == null ? "–" : TIME.format(instant);
    }

    public String ago(Instant instant) {
        if (instant == null) {
            return "never";
        }
        long s = Duration.between(instant, clock.instant()).toSeconds();
        if (s < 60) {
            return s + "s ago";
        }
        if (s < 3600) {
            return (s / 60) + " min ago";
        }
        if (s < 86400) {
            return (s / 3600) + " h ago";
        }
        return (s / 86400) + " d ago";
    }

    public String pct(Double probability) {
        return probability == null ? "–" : Text.pct(probability);
    }

    public String num(Double value, int decimals) {
        return value == null || value.isNaN() ? "–" : Text.num(value, decimals);
    }

    public String signedPct(double fraction) {
        return (fraction >= 0 ? "+" : "") + Text.num(fraction * 100, 1) + "%";
    }

    /** Width in percent for a share between 0 and 1. */
    public long share(Double fraction) {
        return fraction == null ? 0 : Math.round(Math.clamp(fraction * 100, 0, 100));
    }

    /** Width in percent of |value| relative to max. */
    public long relative(double value, double max) {
        return max == 0 ? 0 : Math.round(Math.clamp(Math.abs(value) / max * 100, 0, 100));
    }

    public String duration(java.time.Duration d) {
        if (d == null) {
            return "–";
        }
        long s = d.toSeconds();
        return s < 120 ? s + " s" : s < 7200 ? (s / 60) + " min" : (s / 3600) + " h";
    }

    /**
     * Escapes text for HTML, then turns evidence citations such as [E2] or [E1, E4] into links to the evidence item
     * on the same page. Used with th:utext; the escaping happens first, so user or agent text cannot inject markup.
     */
    public String cite(String text) {
        if (text == null) {
            return "";
        }
        String escaped = org.springframework.web.util.HtmlUtils.htmlEscape(text);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([^\\]<>]{1,60})\\]").matcher(escaped);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1).replaceAll("\\bE(\\d{1,3})\\b", "<a class=\"cite\" href=\"#E$1\">E$1</a>");
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("[" + inner + "]"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Width in percent of a log-odds contribution bar; 0 for contributions that lower risk. */
    public long barWidth(double logOdds) {
        return Math.round(Math.clamp(logOdds * 15, 0, 100));
    }

    /** Bootstrap contextual colour for statuses, severities, risk levels and priorities. */
    public String tone(Object status) {
        if (status == null) {
            return "secondary";
        }
        return switch (status.toString()) {
            case "NORMAL", "LOW", "PASS", "APPROVED", "CLOSED", "COMPLETED", "ONLINE", "SUCCESS", "MEASURED", "EDGE", "VERIFIED" -> "success";
            case "WARNING", "MEDIUM", "PROPOSED", "OPEN", "STALE", "AI_INTERPRETATION", "SIMULATOR" -> "warning";
            case "CRITICAL", "HIGH", "URGENT", "DEFECT", "REJECTED", "OFFLINE", "ERROR", "DENIED", "MISMATCH", "FAIL" -> "danger";
            case "REVIEWED", "PLANNED", "MODEL_PREDICTION", "RULE_BASED", "DOCUMENTED" -> "info";
            default -> "secondary";
        };
    }
}

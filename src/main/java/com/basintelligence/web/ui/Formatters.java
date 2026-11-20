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

    /** e.g. "data 98% complete · 2 min ago": how complete the last hour of readings is, and when the machine last reported. */
    public String dataLine(double completeness, Instant lastSeenAt) {
        return "data " + Text.pct(completeness) + " complete · " + ago(lastSeenAt);
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

    /** The time between two instants, e.g. "40 s"; "–" if either is missing. */
    public String elapsed(Instant from, Instant to) {
        return from == null || to == null ? "–" : duration(Duration.between(from, to));
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

    private static final java.util.regex.Pattern HEADING = java.util.regex.Pattern.compile("#{1,6}\\s+(.*)");
    private static final java.util.regex.Pattern TABLE_SEPARATOR = java.util.regex.Pattern.compile("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?");

    /**
     * Kiro's Markdown reply as HTML, for th:utext inside an element with {@code white-space: pre-wrap} (rendered in the
     * controller: templates may not call beans inside th:utext). Every line is
     * HTML-escaped first; then only fixed tags are added: headings, bold, inline code, pipe tables, and links from
     * "INV-12" to that report. Lists stay as text lines. So nothing in the reply can inject markup or scripts.
     */
    public String markdown(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (isTableRow(line) && i + 1 < lines.length && TABLE_SEPARATOR.matcher(lines[i + 1].strip()).matches()) {
                out.append("<div class=\"table-responsive\"><table class=\"table table-sm table-bordered small w-auto mb-1\"><thead><tr>");
                cells(line).forEach(c -> out.append("<th>").append(inline(c)).append("</th>"));
                out.append("</tr></thead><tbody>");
                i += 2;
                while (i < lines.length && isTableRow(lines[i])) {
                    out.append("<tr>");
                    cells(lines[i]).forEach(c -> out.append("<td>").append(inline(c)).append("</td>"));
                    out.append("</tr>");
                    i++;
                }
                out.append("</tbody></table></div>");
                i--;
                continue;
            }
            java.util.regex.Matcher heading = HEADING.matcher(line.strip());
            if (heading.matches()) {
                out.append("<div class=\"fw-semibold mt-2\">").append(inline(heading.group(1))).append("</div>");
                continue;
            }
            out.append(inline(line));
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static boolean isTableRow(String line) {
        String s = line.strip();
        return s.length() > 1 && s.startsWith("|") && s.endsWith("|");
    }

    private static java.util.List<String> cells(String row) {
        String s = row.strip();
        return java.util.Arrays.stream(s.substring(1, s.length() - 1).split("\\|", -1)).map(String::strip).toList();
    }

    private static String inline(String text) {
        String s = org.springframework.web.util.HtmlUtils.htmlEscape(text);
        s = s.replaceAll("`([^`]{1,200})`", "<code>$1</code>");
        s = s.replaceAll("\\*\\*([^*]{1,400})\\*\\*", "<strong>$1</strong>");
        return s.replaceAll("\\bINV-(\\d{1,9})\\b", "<a href=\"/investigations/$1\">INV-$1</a>");
    }

    /** Bootstrap contextual colour for statuses, severities, risk levels and priorities. */
    public String tone(Object status) {
        if (status == null) {
            return "secondary";
        }
        return switch (status.toString()) {
            case "NORMAL", "LOW", "PASS", "APPROVED", "CLOSED", "COMPLETED", "ONLINE", "SUCCESS", "MEASURED", "EDGE", "VERIFIED", "DONE" -> "success";
            case "WARNING", "MEDIUM", "PROPOSED", "OPEN", "STALE", "AI_INTERPRETATION", "SIMULATOR", "RUNNING" -> "warning";
            case "CRITICAL", "HIGH", "URGENT", "DEFECT", "REJECTED", "OFFLINE", "ERROR", "DENIED", "MISMATCH", "FAIL", "FAILED", "TIMED_OUT" -> "danger";
            case "REVIEWED", "PLANNED", "MODEL_PREDICTION", "RULE_BASED", "DOCUMENTED" -> "info";
            default -> "secondary";
        };
    }
}

package com.basintelligence.web.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;

class FormattersMarkdownTest {

    private final Formatters fmt = new Formatters(Clock.systemUTC());

    @Test
    void rendersTheMarkdownKiroUsesAndLinksReports() {
        String html = fmt.markdown("""
                ## Measured evidence
                | Channel | Mean |
                |---|---|
                | **Vibration** | 2.05 mm/s |
                - risk is `LOW`
                Report: INV-5""");

        assertThat(html).contains("<div class=\"fw-semibold mt-2\">Measured evidence</div>",
                "<th>Channel</th><th>Mean</th>", "<td><strong>Vibration</strong></td><td>2.05 mm/s</td>",
                "- risk is <code>LOW</code>", "<a href=\"/investigations/5\">INV-5</a>");
    }

    @Test
    void escapesEverythingElseSoAReplyCannotInjectMarkup() {
        String html = fmt.markdown("""
                <script>alert(1)</script> **<img src=x onerror=alert(1)>**
                | <b>x</b> | `<i>` |
                |---|---|
                | [link](javascript:alert(1)) | "quoted" |""");

        assertThat(html).doesNotContain("<script>", "<img", "<b>", "<i>", "href=\"javascript")
                .contains("&lt;script&gt;", "<strong>&lt;img src=x onerror=alert(1)&gt;</strong>",
                        "<th>&lt;b&gt;x&lt;/b&gt;</th>", "<code>&lt;i&gt;</code>");
    }
}

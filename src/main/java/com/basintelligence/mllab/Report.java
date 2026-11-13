package com.basintelligence.mllab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal Markdown report builder for the evidence documents in ml/reports. */
public final class Report {

    private final StringBuilder md = new StringBuilder();

    public Report h1(String s) { md.append("# ").append(s).append("\n\n"); return this; }
    public Report h2(String s) { md.append("## ").append(s).append("\n\n"); return this; }
    public Report p(String s) { md.append(s).append("\n\n"); return this; }

    public Report table(List<String> header, List<List<String>> rows) {
        md.append("| ").append(String.join(" | ", header)).append(" |\n|");
        header.forEach(h -> md.append("---|"));
        md.append('\n');
        for (List<String> r : rows) md.append("| ").append(String.join(" | ", r)).append(" |\n");
        md.append('\n');
        return this;
    }

    public Report metrics(String title, Map<String, Double> metrics) {
        return table(List.of("Metric", title), metrics.entrySet().stream()
                .map(e -> List.of(e.getKey(), fmt(e.getValue()))).toList());
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, md.toString());
        System.out.println("  wrote " + file);
    }

    public static String fmt(double v) {
        if (Double.isNaN(v)) return "n/a";
        if (v == Math.rint(v) && Math.abs(v) < 1e7) return String.format(Locale.ROOT, "%.0f", v);
        return String.format(Locale.ROOT, Math.abs(v) >= 100 ? "%.1f" : "%.4f", v);
    }
}

package com.basintelligence.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/** Small formatting and JDBC helpers shared across modules. */
public final class Text {

    private Text() {
    }

    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max - 1) + "…";
    }

    public static String num(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    public static String pct(double probability) {
        return String.format(Locale.ROOT, "%.0f%%", probability * 100);
    }

    public static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * Client-supplied file name reduced to a harmless display value: no directories, only letters, digits, dot,
     * underscore, space and hyphen, at most 255 characters. Never used to build a storage path.
     */
    public static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String base = filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        String cleaned = base.replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("^\\.+", "_");
        return truncate(cleaned.isBlank() ? "_" : cleaned, 255);
    }
}

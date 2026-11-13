package com.basintelligence.config;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Connects the {@code neon} profile to the credentials that {@code neon init} writes to {@code .env.local}
 * (never committed). Neon provides URIs such as {@code postgresql://user:pass@host/db?sslmode=require}; this
 * converts them into the JDBC form the application expects:
 * <ul>
 * <li>{@code DATABASE_URL} = {@code jdbc:postgresql://host/db?sslmode=require&channelBinding=require}</li>
 * <li>{@code DATABASE_USERNAME}, {@code DATABASE_PASSWORD}</li>
 * </ul>
 * Precedence: an explicit JDBC {@code DATABASE_URL} in the real environment wins; otherwise
 * {@code DATABASE_URL_UNPOOLED} (preferred, direct connection for Flyway and a small pool) or {@code DATABASE_URL}
 * from the environment or {@code .env.local} is converted. Credentials are never logged.
 */
public class NeonEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "neonConnection";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.matchesProfiles("neon")) {
            return;
        }
        Map<String, String> file = readDotEnv(Path.of(environment.getProperty("bas.neon.env-file", ".env.local")));
        // App secrets (BAS_*) from the same git-ignored file; real environment variables still take precedence.
        Map<String, Object> appSecrets = new HashMap<>();
        file.forEach((k, v) -> {
            if (k.startsWith("BAS_")) {
                appSecrets.put(k, v);
            }
        });
        if (!appSecrets.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME + "AppSecrets", appSecrets));
        }
        String explicit = unquote(environment.getProperty("DATABASE_URL"));
        if (explicit != null && explicit.startsWith("jdbc:")) {
            return;
        }
        String uri = first(unquote(environment.getProperty("DATABASE_URL_UNPOOLED")), file.get("DATABASE_URL_UNPOOLED"),
                explicit, file.get("DATABASE_URL"));
        if (uri == null) {
            return; // the datasource will fail with a clear "DATABASE_URL not set" message
        }
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, new HashMap<>(toJdbc(uri))));
    }

    /** Converts a Neon/libpq URI to JDBC properties. TLS is always required. */
    static Map<String, Object> toJdbc(String uri) {
        URI u = URI.create(uri.strip());
        if (u.getScheme() == null || !u.getScheme().startsWith("postgres") || u.getRawUserInfo() == null) {
            throw new IllegalArgumentException("Expected postgresql://user:password@host/db connection string");
        }
        String[] userInfo = u.getRawUserInfo().split(":", 2);
        String port = u.getPort() > 0 ? ":" + u.getPort() : "";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("DATABASE_URL", "jdbc:postgresql://" + u.getHost() + port + u.getPath()
                + "?sslmode=require&channelBinding=require&ApplicationName=bas-intelligence");
        out.put("DATABASE_USERNAME", URLDecoder.decode(userInfo[0], StandardCharsets.UTF_8));
        out.put("DATABASE_PASSWORD", userInfo.length > 1 ? URLDecoder.decode(userInfo[1], StandardCharsets.UTF_8) : "");
        return out;
    }

    /** Minimal .env parser: KEY=value lines, optional surrounding quotes, # comments. */
    static Map<String, String> readDotEnv(Path path) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(path)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String s = line.strip();
                if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) {
                    continue;
                }
                if (s.startsWith("export ")) {
                    s = s.substring(7).strip();
                }
                String key = s.substring(0, s.indexOf('=')).strip();
                String value = s.substring(s.indexOf('=') + 1).strip();
                values.put(key, unquote(value));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
        return values;
    }

    static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String first(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    @Override
    public int getOrder() {
        // After config files are loaded (so profiles are known).
        return Ordered.LOWEST_PRECEDENCE;
    }
}

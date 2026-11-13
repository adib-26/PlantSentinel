package com.basintelligence.telemetry;

import static com.basintelligence.common.Text.instant;
import static com.basintelligence.common.Text.utc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.NotFoundException;
import com.basintelligence.factory.Factory;
import com.basintelligence.factory.FactoryService;

/**
 * Registered BAS edge gateways and their API keys.
 * <p>
 * A key looks like {@code bas_gw_<8 hex>_<43 base64url>}: 256 random bits plus a public identifier. The database
 * keeps only the SHA-256 of the whole key (a slow password hash is unnecessary for a random 256-bit secret, and a
 * fast hash allows an indexed lookup on every request) and the public prefix for display. The key itself is
 * returned once, on creation or rotation, and cannot be recovered afterwards.
 */
@Service
public class EdgeGatewayService {

    public static final String KEY_PREFIX = "bas_gw_";
    private static final String CODE_PATTERN = "[A-Za-z0-9._:-]{3,80}";
    /** last_used_at is refreshed at most this often, so steady ingestion does not write on every batch. */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

    public record EdgeGateway(long id, UUID factoryId, String factoryCode, String code, String description,
            String keyPrefix, boolean enabled, String createdBy, Instant createdAt, Instant rotatedAt, Instant revokedAt,
            Instant lastUsedAt) {
    }

    /** A newly issued key. {@code apiKey} is shown to the administrator once and never stored. */
    public record IssuedKey(EdgeGateway gateway, String apiKey) {
    }

    private static final RowMapper<EdgeGateway> MAPPER = (rs, n) -> new EdgeGateway(rs.getLong("id"),
            rs.getObject("factory_id", UUID.class), rs.getString("factory_code"), rs.getString("code"),
            rs.getString("description"), rs.getString("key_prefix"), rs.getBoolean("enabled"), rs.getString("created_by"),
            instant(rs, "created_at"), instant(rs, "rotated_at"), instant(rs, "revoked_at"), instant(rs, "last_used_at"));

    private static final String SELECT = """
            SELECT g.*, f.code AS factory_code FROM edge_gateways g JOIN factories f ON f.id = g.factory_id""";

    private final JdbcClient jdbc;
    private final FactoryService factories;
    private final AuditService audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public EdgeGatewayService(JdbcClient jdbc, FactoryService factories, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.factories = factories;
        this.audit = audit;
        this.clock = clock;
    }

    public List<EdgeGateway> list() {
        return jdbc.sql(SELECT + " ORDER BY g.code").query(MAPPER).list();
    }

    public EdgeGateway get(String code) {
        return jdbc.sql(SELECT + " WHERE g.code = :c").param("c", code).query(MAPPER).optional()
                .orElseThrow(() -> new NotFoundException("Edge gateway not found: " + code));
    }

    @Transactional
    public IssuedKey create(String code, String factoryCode, String description, String actor) {
        if (code == null || !code.matches(CODE_PATTERN)) {
            throw new IllegalArgumentException("Gateway code must be 3-80 letters, digits or . _ : -");
        }
        Factory factory = factories.getByCode(factoryCode);
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM edge_gateways WHERE code = :c").param("c", code)
                .query(Long.class).single() > 0;
        if (exists) {
            throw new InvalidStateException("Edge gateway already registered: " + code);
        }
        String[] key = newKey();
        jdbc.sql("""
                INSERT INTO edge_gateways (factory_id, code, description, key_prefix, key_hash, enabled, created_by, created_at)
                VALUES (:f, :c, :d, :p, :h, TRUE, :by, :at)""")
                .param("f", factory.id()).param("c", code)
                .param("d", description == null || description.isBlank() ? null : description.strip())
                .param("p", key[1]).param("h", hash(key[0])).param("by", actor).param("at", utc(clock.instant()))
                .update();
        audit.record(actor, "GATEWAY_CREATED", "gateway:" + code, "factory " + factory.code() + ", key " + key[1] + "…");
        return new IssuedKey(get(code), key[0]);
    }

    /** Issues a new key; the old one stops working immediately. Also re-enables a revoked gateway. */
    @Transactional
    public IssuedKey rotate(String code, String actor) {
        get(code);
        String[] key = newKey();
        jdbc.sql("""
                UPDATE edge_gateways SET key_prefix = :p, key_hash = :h, enabled = TRUE, revoked_at = NULL, rotated_at = :at
                WHERE code = :c""")
                .param("p", key[1]).param("h", hash(key[0])).param("at", utc(clock.instant())).param("c", code).update();
        audit.record(actor, "GATEWAY_KEY_ROTATED", "gateway:" + code, "new key " + key[1] + "…");
        return new IssuedKey(get(code), key[0]);
    }

    @Transactional
    public EdgeGateway revoke(String code, String actor) {
        int updated = jdbc.sql("UPDATE edge_gateways SET enabled = FALSE, revoked_at = :at WHERE code = :c AND enabled = TRUE")
                .param("at", utc(clock.instant())).param("c", code).update();
        if (updated != 1) {
            get(code);
            throw new InvalidStateException("Edge gateway " + code + " is already revoked");
        }
        audit.record(actor, "GATEWAY_REVOKED", "gateway:" + code, null);
        return get(code);
    }

    /** Resolves a presented key to an enabled gateway. */
    public Optional<EdgePrincipal> authenticate(String apiKey) {
        if (apiKey == null || !apiKey.startsWith(KEY_PREFIX) || apiKey.length() > 200) {
            return Optional.empty();
        }
        Optional<EdgeGateway> gateway = jdbc.sql(SELECT + " WHERE g.key_hash = :h AND g.enabled = TRUE")
                .param("h", hash(apiKey)).query(MAPPER).optional();
        gateway.ifPresent(g -> {
            Instant now = clock.instant();
            if (g.lastUsedAt() == null || g.lastUsedAt().isBefore(now.minus(TOUCH_INTERVAL))) {
                jdbc.sql("UPDATE edge_gateways SET last_used_at = :at WHERE id = :id")
                        .param("at", utc(now)).param("id", g.id()).update();
            }
        });
        return gateway.map(g -> new EdgePrincipal(g.code(), g.factoryId()));
    }

    /** @return {full key, public prefix} */
    private String[] newKey() {
        byte[] id = new byte[4];
        byte[] secret = new byte[32];
        random.nextBytes(id);
        random.nextBytes(secret);
        String prefix = KEY_PREFIX + HexFormat.of().formatHex(id);
        return new String[] {prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret), prefix};
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

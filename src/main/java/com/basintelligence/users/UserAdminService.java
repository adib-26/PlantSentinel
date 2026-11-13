package com.basintelligence.users;

import static com.basintelligence.common.Text.instant;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.InvalidStateException;
import com.basintelligence.common.NotFoundException;
import com.basintelligence.factory.Factory;
import com.basintelligence.factory.FactoryService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * User management for administrators. Passwords are hashed with BCrypt (via the delegating encoder) and never
 * logged or returned. Every change is audited and ends the user's sessions (see {@link UserAccessChanged}).
 */
@Service
public class UserAdminService {

    public static final int MIN_PASSWORD_LENGTH = 12;
    /** BCrypt only uses the first 72 bytes of a password; longer ones are refused rather than silently cut. */
    public static final int MAX_PASSWORD_BYTES = 72;

    public record UserSummary(String username, UserRole role, List<String> roles, String factoryCode, boolean enabled,
            java.time.Instant createdAt) {
    }

    public record NewUser(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{2,39}", message = "use 3-40 lower-case letters, digits, '.', '_' or '-'") String username,
            @NotBlank @Size(min = MIN_PASSWORD_LENGTH, max = MAX_PASSWORD_BYTES) String password,
            @NotNull UserRole role,
            @Size(max = 40) String factoryCode) {
    }

    /** Fields left null are not changed. */
    public record UpdateUser(UserRole role, @Size(max = 40) String factoryCode, Boolean enabled) {
    }

    public record PasswordReset(@NotBlank @Size(min = MIN_PASSWORD_LENGTH, max = MAX_PASSWORD_BYTES) String password) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final FactoryService factories;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public UserAdminService(JdbcClient jdbc, PasswordEncoder encoder, FactoryService factories, AuditService audit,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.factories = factories;
        this.audit = audit;
        this.events = events;
    }

    public List<UserSummary> list() {
        return jdbc.sql("""
                SELECT u.username, u.roles, u.enabled, u.created_at, f.code AS factory_code
                FROM users u LEFT JOIN factories f ON f.id = u.factory_id ORDER BY u.username""")
                .query((rs, n) -> {
                    List<String> roles = roles(rs.getString("roles"));
                    return new UserSummary(rs.getString("username"), UserRole.primaryOf(roles), roles,
                            rs.getString("factory_code"), rs.getBoolean("enabled"), instant(rs, "created_at"));
                }).list();
    }

    public UserSummary get(String username) {
        return list().stream().filter(u -> u.username().equals(username)).findFirst()
                .orElseThrow(() -> new NotFoundException("User not found: " + username));
    }

    @Transactional
    public UserSummary create(NewUser request, String actor) {
        String username = request.username().strip().toLowerCase(Locale.ROOT);
        checkPassword(username, request.password());
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM users WHERE username = :u").param("u", username)
                .query(Long.class).single() > 0;
        if (exists) {
            throw new InvalidStateException("User already exists: " + username);
        }
        Factory factory = factoryFor(request.factoryCode());
        jdbc.sql("""
                INSERT INTO users (id, username, password_hash, roles, enabled, factory_id)
                VALUES (:id, :u, :hash, :roles, TRUE, :f)""")
                .param("id", UUID.randomUUID()).param("u", username).param("hash", encoder.encode(request.password()))
                .param("roles", request.role().csv()).param("f", factory.id())
                .update();
        audit.record(actor, "USER_CREATED", "user:" + username, request.role() + " in " + factory.code());
        return get(username);
    }

    @Transactional
    public UserSummary update(String username, UpdateUser request, String actor) {
        UserSummary current = get(username);
        UserRole role = request.role() == null ? current.role() : request.role();
        boolean enabled = request.enabled() == null ? current.enabled() : request.enabled();
        if (username.equals(actor) && (!enabled || role != UserRole.ADMIN)) {
            throw new InvalidStateException("You cannot disable your own account or remove your own administrator role");
        }
        if (current.role() == UserRole.ADMIN && current.enabled() && (role != UserRole.ADMIN || !enabled) && enabledAdmins() <= 1) {
            throw new InvalidStateException("At least one enabled administrator must remain");
        }
        Factory factory = request.factoryCode() == null || request.factoryCode().isBlank()
                ? current.factoryCode() == null ? factories.defaultFactory() : factories.getByCode(current.factoryCode())
                : factories.getByCode(request.factoryCode().strip());
        jdbc.sql("UPDATE users SET roles = :roles, enabled = :enabled, factory_id = :f WHERE username = :u")
                .param("roles", role.csv()).param("enabled", enabled).param("f", factory.id()).param("u", username)
                .update();
        StringBuilder changes = new StringBuilder();
        if (role != current.role()) {
            changes.append("role ").append(current.role()).append(" -> ").append(role).append("; ");
        }
        if (!factory.code().equals(current.factoryCode())) {
            changes.append("factory ").append(current.factoryCode()).append(" -> ").append(factory.code()).append("; ");
        }
        if (enabled != current.enabled()) {
            changes.append(enabled ? "enabled" : "disabled").append("; ");
        }
        if (!changes.isEmpty()) {
            audit.record(actor, enabled == current.enabled() ? "USER_UPDATED" : enabled ? "USER_ENABLED" : "USER_DISABLED",
                    "user:" + username, changes.toString().strip());
            events.publishEvent(new UserAccessChanged(username));
        }
        return get(username);
    }

    @Transactional
    public void resetPassword(String username, String password, String actor) {
        get(username);
        checkPassword(username, password);
        jdbc.sql("UPDATE users SET password_hash = :hash WHERE username = :u")
                .param("hash", encoder.encode(password)).param("u", username).update();
        audit.record(actor, "USER_PASSWORD_RESET", "user:" + username, null);
        events.publishEvent(new UserAccessChanged(username));
    }

    /** Length rules plus two cheap checks; the password itself never appears in messages or logs. */
    static void checkPassword(String username, String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("The password must have at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("The password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        if (password.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("The password must not contain the username");
        }
        if (password.chars().distinct().count() < 5) {
            throw new IllegalArgumentException("The password is too repetitive");
        }
    }

    private Factory factoryFor(String factoryCode) {
        return factoryCode == null || factoryCode.isBlank() ? factories.defaultFactory() : factories.getByCode(factoryCode.strip());
    }

    private long enabledAdmins() {
        return jdbc.sql("SELECT roles FROM users WHERE enabled = TRUE").query(String.class).list().stream()
                .filter(r -> roles(r).contains("ADMIN")).count();
    }

    private static List<String> roles(String csv) {
        return csv == null ? List.of() : Arrays.stream(csv.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}

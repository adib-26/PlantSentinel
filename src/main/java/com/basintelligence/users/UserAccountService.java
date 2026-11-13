package com.basintelligence.users;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.basintelligence.config.BasProperties;

/**
 * Application users stored in the {@code users} table with BCrypt hashes. Users listed in configuration are
 * created on first start only; existing accounts (and changed passwords) are never overwritten.
 */
@Service
public class UserAccountService implements UserDetailsService, org.springframework.security.core.userdetails.UserDetailsPasswordService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    private record Row(String username, String hash, String roles, boolean enabled) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;

    public UserAccountService(JdbcClient jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        Row row = find(username).orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        return User.withUsername(row.username()).password(row.hash()).disabled(!row.enabled())
                .roles(Arrays.stream(row.roles().split(",")).map(String::strip).filter(r -> !r.isEmpty())
                        .toArray(String[]::new))
                .build();
    }

    /**
     * Called by Spring Security after a successful login when the stored hash uses an outdated format (for example
     * a BCrypt hash created before hashes carried the {@code {bcrypt}} prefix). The password is re-hashed in the
     * current format; the plain password is never stored.
     */
    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails user, String newEncodedPassword) {
        jdbc.sql("UPDATE users SET password_hash = :hash WHERE username = :u")
                .param("hash", newEncodedPassword).param("u", user.getUsername()).update();
        return User.withUserDetails(user).password(newEncodedPassword).build();
    }

    @Transactional
    public void seed(BasProperties.Security security, UUID factoryId) {
        int created = 0;
        for (BasProperties.User u : security.users()) {
            if (u.username() == null || u.username().isBlank() || u.password() == null || u.password().isBlank()) {
                continue;
            }
            if (find(u.username()).isPresent()) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO users (id, username, password_hash, roles, enabled, factory_id)
                    VALUES (:id, :username, :hash, :roles, TRUE, :factoryId)""")
                    .param("id", UUID.randomUUID())
                    .param("username", u.username())
                    .param("hash", encoder.encode(u.password()))
                    .param("roles", String.join(",", u.roles()))
                    .param("factoryId", factoryId)
                    .update();
            created++;
        }
        long total = jdbc.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
        if (created > 0) {
            log.info("Created {} user account(s) from configuration", created);
        }
        if (total == 0) {
            log.warn("No users exist. Set BAS_ENGINEER_PASSWORD (and friends) and restart to create accounts.");
        }
    }

    private Optional<Row> find(String username) {
        return jdbc.sql("SELECT username, password_hash, roles, enabled FROM users WHERE username = :u")
                .param("u", username)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
                .optional();
    }
}

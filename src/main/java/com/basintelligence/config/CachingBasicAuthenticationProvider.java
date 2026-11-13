package com.basintelligence.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * HTTP Basic sends the password with every API request, and checking it with BCrypt costs about 70-100 ms of CPU
 * by design. Under a burst of requests that cost queues every request behind the others. This provider remembers a
 * successful check for a short time, keyed by a SHA-256 of username and password with a per-process random salt, so
 * repeated requests skip BCrypt. Failed logins are never cached; the user is reloaded on every request, so a disabled
 * account or changed roles take effect immediately, and a changed password stops matching the cached key.
 */
public class CachingBasicAuthenticationProvider implements AuthenticationProvider {

    private record Entry(String username, Instant expires) {
    }

    private final DaoAuthenticationProvider delegate;
    private final org.springframework.security.core.userdetails.UserDetailsService users;
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;
    private final byte[] salt = new byte[32];
    private final Map<String, Entry> verified = new ConcurrentHashMap<>();

    public CachingBasicAuthenticationProvider(DaoAuthenticationProvider delegate,
            org.springframework.security.core.userdetails.UserDetailsService users, Duration ttl, int maxEntries, Clock clock) {
        this.delegate = delegate;
        this.users = users;
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
        new java.security.SecureRandom().nextBytes(salt);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        Object credentials = authentication.getCredentials();
        if (credentials == null) {
            return delegate.authenticate(authentication);
        }
        String key = key(username, credentials.toString());
        Instant now = clock.instant();
        Entry entry = verified.get(key);
        if (entry != null && entry.expires().isAfter(now)) {
            var user = users.loadUserByUsername(username); // still enforces disabled / locked / current roles
            if (user.isEnabled() && user.isAccountNonLocked() && user.isAccountNonExpired() && user.isCredentialsNonExpired()) {
                var result = UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
                result.setDetails(authentication.getDetails());
                return result;
            }
            verified.remove(key);
        }
        Authentication result = delegate.authenticate(authentication); // BCrypt; throws on bad credentials
        if (verified.size() >= maxEntries) {
            verified.entrySet().removeIf(e -> !e.getValue().expires().isAfter(now));
            if (verified.size() >= maxEntries) {
                verified.clear();
            }
        }
        verified.put(key, new Entry(username, now.plus(ttl)));
        return result;
    }

    /** Forgets cached checks for a user, after a password reset, disabling or a role change. */
    public void evict(String username) {
        verified.values().removeIf(e -> e.username().equals(username));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    int cachedEntries() {
        return verified.size();
    }

    private String key(String username, String password) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(salt);
            sha.update(username.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

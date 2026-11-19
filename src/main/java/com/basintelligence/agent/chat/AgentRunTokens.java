package com.basintelligence.agent.chat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.basintelligence.common.AgentRun;

/**
 * Short-lived MCP credentials for Kiro assistant runs. Each run gets its own random token (256 bits), valid until the
 * run ends or its lifetime passes, whichever comes first. Only the SHA-256 of a token is kept, in memory: a restart
 * revokes every token, and the runs that used them are stopped with the app.
 */
@Component
public class AgentRunTokens {

    private record Grant(AgentRun run, Instant expiresAt) {
    }

    private final Map<String, Grant> byHash = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public AgentRunTokens(Clock clock) {
        this.clock = clock;
    }

    public String issue(AgentRun run, Duration lifetime) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = AgentRun.TOKEN_PREFIX + HexFormat.of().formatHex(bytes);
        Instant now = clock.instant();
        byHash.values().removeIf(g -> !now.isBefore(g.expiresAt()));
        byHash.put(hash(token), new Grant(run, now.plus(lifetime)));
        return token;
    }

    /** The run a token was issued for, if the token is known and still valid. */
    public Optional<AgentRun> verify(String token) {
        if (token == null || !token.startsWith(AgentRun.TOKEN_PREFIX) || token.length() > 200) {
            return Optional.empty();
        }
        String key = hash(token);
        Grant grant = byHash.get(key);
        if (grant == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(grant.expiresAt())) {
            byHash.remove(key, grant);
            return Optional.empty();
        }
        return Optional.of(grant.run());
    }

    public void revoke(String token) {
        if (token != null) {
            byHash.remove(hash(token));
        }
    }

    int active() {
        return byHash.size();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

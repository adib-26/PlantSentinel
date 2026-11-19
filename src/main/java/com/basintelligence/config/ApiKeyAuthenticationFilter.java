package com.basintelligence.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.basintelligence.common.AgentRun;
import com.basintelligence.common.AuditService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates the Kiro MCP client by the X-API-Key header. Two kinds of key are accepted:
 * <ul>
 * <li>the shared agent key, compared in constant time. If no key is configured, it is never accepted (fail closed);</li>
 * <li>a short-lived run token ({@link AgentRun#TOKEN_PREFIX}) that the web app issued for one Kiro assistant run. The
 * authentication then carries the {@link AgentRun}, so the run sees only its engineer's machines. A run token that is
 * unknown, expired or revoked is refused and never compared with the shared key.</li>
 * </ul>
 * Refusals are audited, at most once a minute per client address.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final byte[] expected;
    private final String principal;
    private final String role;
    private final Function<String, Optional<AgentRun>> runTokens;
    private final AuditService audit;

    public ApiKeyAuthenticationFilter(String expectedKey, String principal, String role) {
        this(expectedKey, principal, role, null, null);
    }

    /**
     * @param runTokens verifies run tokens; null accepts none
     * @param audit     records refusals; may be null
     */
    public ApiKeyAuthenticationFilter(String expectedKey, String principal, String role,
            Function<String, Optional<AgentRun>> runTokens, AuditService audit) {
        this.expected = expectedKey == null || expectedKey.isBlank() ? null : expectedKey.getBytes(StandardCharsets.UTF_8);
        this.principal = principal;
        this.role = role;
        this.runTokens = runTokens;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        UsernamePasswordAuthenticationToken auth = authenticate(provided);
        if (auth == null) {
            refuse(request, provided);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or invalid " + HEADER + " header\"}");
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private UsernamePasswordAuthenticationToken authenticate(String provided) {
        if (provided == null) {
            return null;
        }
        if (provided.startsWith(AgentRun.TOKEN_PREFIX)) {
            Optional<AgentRun> run = runTokens == null ? Optional.empty() : runTokens.apply(provided);
            return run.map(r -> {
                var token = token();
                token.setDetails(r);
                return token;
            }).orElse(null);
        }
        if (expected == null || !MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return token();
    }

    private UsernamePasswordAuthenticationToken token() {
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    /** Never logs the key itself. */
    private void refuse(HttpServletRequest request, String provided) {
        if (audit == null) {
            return;
        }
        String client = request.getRemoteAddr();
        String presented = provided == null ? "no key" : provided.startsWith(AgentRun.TOKEN_PREFIX)
                ? "unknown, expired or revoked run token" : "unrecognised key";
        try {
            audit.recordThrottled("agent-auth:" + client, Duration.ofMinutes(1), "anonymous", "AGENT_AUTH_FAILED",
                    request.getRequestURI(), presented + " from " + client);
        } catch (DataAccessException e) {
            log.debug("Could not audit agent authentication failure: {}", e.getMessage());
        }
    }
}

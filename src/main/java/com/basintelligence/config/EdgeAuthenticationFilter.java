package com.basintelligence.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.basintelligence.common.AuditService;
import com.basintelligence.telemetry.EdgeGatewayService;
import com.basintelligence.telemetry.EdgePrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates BAS edge gateways by the X-API-Key header, in this order:
 * <ol>
 * <li>a per-gateway key ({@code bas_gw_...}) registered by an administrator, looked up by its SHA-256 hash; the
 * gateway is bound to one factory;</li>
 * <li>the optional shared key {@code bas.security.edge-api-key}, compared in constant time, for existing edge
 * nodes; it has no factory restriction.</li>
 * </ol>
 * Fails closed: no or unknown key gives 401 (audited, throttled per client address). If the key store is unreachable
 * the gateway gets a retryable 503, like any other database outage, and keeps its readings queued.
 */
public class EdgeAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(EdgeAuthenticationFilter.class);
    public static final String HEADER = ApiKeyAuthenticationFilter.HEADER;

    private final byte[] sharedKey;
    private final EdgeGatewayService gateways;
    private final AuditService audit;

    public EdgeAuthenticationFilter(String sharedKey, EdgeGatewayService gateways, AuditService audit) {
        this.sharedKey = sharedKey == null || sharedKey.isBlank() ? null : sharedKey.getBytes(StandardCharsets.UTF_8);
        this.gateways = gateways;
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        Optional<EdgePrincipal> principal;
        try {
            principal = resolve(provided);
        } catch (DataAccessException e) {
            log.warn("Edge key lookup failed, database unavailable: {}", e.getMessage());
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, "30");
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":503,\"detail\":\"The database is temporarily unavailable. Retry later.\"}");
            return;
        }
        if (principal.isEmpty()) {
            auditFailure(request, provided);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or invalid " + HEADER + " header\"}");
            return;
        }
        var auth = new UsernamePasswordAuthenticationToken(principal.get(), null, List.of(new SimpleGrantedAuthority("ROLE_EDGE")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<EdgePrincipal> resolve(String provided) {
        if (provided == null || provided.isBlank()) {
            return Optional.empty();
        }
        if (provided.startsWith(EdgeGatewayService.KEY_PREFIX)) {
            return gateways.authenticate(provided);
        }
        if (sharedKey != null && MessageDigest.isEqual(sharedKey, provided.getBytes(StandardCharsets.UTF_8))) {
            return Optional.of(EdgePrincipal.sharedKey());
        }
        return Optional.empty();
    }

    private void auditFailure(HttpServletRequest request, String provided) {
        String client = request.getRemoteAddr();
        // Only the public part of a gateway key is recorded, never a full or shared secret.
        String presented = provided == null || provided.isBlank() ? "no key"
                : provided.startsWith(EdgeGatewayService.KEY_PREFIX) && provided.length() >= 15
                        ? "key " + provided.substring(0, 15) + "…" : "unrecognised key";
        try {
            audit.recordThrottled("edge-auth:" + client, Duration.ofMinutes(1), "anonymous", "EDGE_AUTH_FAILED",
                    request.getRequestURI(), presented + " from " + client);
        } catch (DataAccessException e) {
            log.debug("Could not audit edge authentication failure: {}", e.getMessage());
        }
    }
}

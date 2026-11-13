package com.basintelligence.users;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Resolves the {@link AccessScope} of the current caller from the Spring Security context.
 * <p>
 * Code that runs outside any request (startup seeding, schedulers, the simulator) has no authentication and gets
 * {@link AccessScope#ALL}. Every web, API, edge and MCP request passes a security filter chain, which always sets an
 * authentication (anonymous at least), so requests never fall into that case. Anonymous requests get
 * {@link AccessScope#NONE}. The scope is computed once per request.
 */
@Service
public class AccessScopeService {

    private static final String ATTRIBUTE = AccessScopeService.class.getName();

    private final JdbcClient jdbc;

    public AccessScopeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public AccessScope current() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null && request.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof AccessScope cached) {
            return cached;
        }
        AccessScope scope = resolve(SecurityContextHolder.getContext().getAuthentication());
        if (request != null) {
            request.setAttribute(ATTRIBUTE, scope, RequestAttributes.SCOPE_REQUEST);
        }
        return scope;
    }

    AccessScope resolve(Authentication auth) {
        if (auth == null) {
            return AccessScope.ALL; // background work, not a request
        }
        if (auth instanceof AnonymousAuthenticationToken || !auth.isAuthenticated()) {
            return AccessScope.NONE;
        }
        Set<String> roles = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).collect(Collectors.toSet());
        if (roles.contains("AGENT")) {
            return AccessScope.ALL; // the Kiro agent key is plant-wide; it can only read and propose
        }
        if (roles.contains("EDGE")) {
            return AccessScope.NONE; // gateways only ingest; their factory is checked by the ingestion endpoint
        }
        return AccessScope.forPerson(auth.getName(), roles, factoryOf(auth.getName()));
    }

    private UUID factoryOf(String username) {
        return jdbc.sql("SELECT factory_id FROM users WHERE username = :u AND enabled = TRUE")
                .param("u", username).query((rs, n) -> rs.getObject(1, UUID.class)).optional().orElse(null);
    }
}

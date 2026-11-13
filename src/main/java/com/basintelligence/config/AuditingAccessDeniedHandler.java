package com.basintelligence.config;

import java.io.IOException;
import java.time.Duration;

import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;

import com.basintelligence.common.AuditService;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes an ACCESS_DENIED (or CSRF_REJECTED) audit entry, at most one per user, path and minute, then answers 403
 * as usual. Covers URL rules, {@code @PreAuthorize} checks and CSRF failures.
 */
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    private final AuditService audit;
    private final AccessDeniedHandler delegate = new AccessDeniedHandlerImpl();

    public AuditingAccessDeniedHandler(AuditService audit) {
        this.audit = audit;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException, ServletException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String actor = auth == null ? "anonymous" : auth.getName();
        String action = e instanceof CsrfException ? "CSRF_REJECTED" : "ACCESS_DENIED";
        String target = request.getMethod() + " " + request.getRequestURI();
        try {
            audit.recordThrottled(action + ":" + actor + ":" + target, Duration.ofMinutes(1), actor, action, target,
                    "from " + request.getRemoteAddr());
        } catch (DataAccessException ex) {
            // never let auditing change the response
        }
        delegate.handle(request, response, e);
    }
}

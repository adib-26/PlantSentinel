package com.basintelligence.config;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.basintelligence.common.AuditService;
import com.basintelligence.common.Text;
import com.basintelligence.users.UserAccessChanged;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Audits sign-ins (web form), failed sign-ins (web form and HTTP Basic) and sign-outs, and applies administrator
 * changes to live credentials: after a password reset, role change or disabling, the user's web sessions end and
 * cached HTTP Basic checks are dropped.
 */
@Component
public class SecurityAuditListener {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditListener.class);

    private final AuditService audit;
    private final MeterRegistry meters;
    private final SessionRegistry sessions;
    private final CachingBasicAuthenticationProvider basicAuth;

    public SecurityAuditListener(AuditService audit, MeterRegistry meters, SessionRegistry sessions,
            CachingBasicAuthenticationProvider basicAuth) {
        this.audit = audit;
        this.meters = meters;
        this.sessions = sessions;
        this.basicAuth = basicAuth;
    }

    @EventListener
    public void loggedIn(InteractiveAuthenticationSuccessEvent event) {
        var auth = event.getAuthentication();
        safely(() -> audit.record(auth.getName(), "LOGIN_SUCCEEDED", "user:" + auth.getName(), "from " + address(auth.getDetails())));
    }

    @EventListener
    public void loginFailed(AbstractAuthenticationFailureEvent event) {
        var auth = event.getAuthentication();
        String username = Text.truncate(auth.getName() == null ? "" : auth.getName().replaceAll("\\p{Cntrl}", "?"), 80);
        String from = address(auth.getDetails());
        meters.counter("bas.security.login.failed").increment();
        safely(() -> audit.recordThrottled("login:" + username + ":" + from, Duration.ofMinutes(1), "anonymous",
                "LOGIN_FAILED", "user:" + username,
                event.getException().getClass().getSimpleName() + " from " + from));
    }

    @EventListener
    public void loggedOut(LogoutSuccessEvent event) {
        var auth = event.getAuthentication();
        safely(() -> audit.record(auth.getName(), "LOGOUT", "user:" + auth.getName(), null));
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void accessChanged(UserAccessChanged event) {
        basicAuth.evict(event.username());
        int ended = 0;
        for (Object principal : sessions.getAllPrincipals()) {
            if (principal instanceof org.springframework.security.core.userdetails.UserDetails u
                    && u.getUsername().equals(event.username())) {
                for (var session : sessions.getAllSessions(principal, false)) {
                    session.expireNow();
                    ended++;
                }
            }
        }
        log.info("Access of {} changed; ended {} web session(s) and cleared cached API credentials", event.username(), ended);
    }

    private static String address(Object details) {
        return details instanceof WebAuthenticationDetails w && w.getRemoteAddress() != null ? w.getRemoteAddress() : "unknown";
    }

    private static void safely(Runnable write) {
        try {
            write.run();
        } catch (DataAccessException e) {
            log.warn("Could not write security audit entry: {}", e.getMessage());
        }
    }
}

package com.basintelligence.config;

import java.util.Map;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import com.basintelligence.common.AuditService;
import com.basintelligence.telemetry.EdgeGatewayService;
import com.basintelligence.users.UserAccountService;

import jakarta.servlet.DispatcherType;

/**
 * Four filter chains, most specific first:
 * <ol>
 * <li>/api/edge/** for BAS edge gateways (X-API-Key, role EDGE): a per-gateway key bound to one factory, or the
 * optional shared key</li>
 * <li>/mcp for the Kiro agent (X-API-Key, role AGENT)</li>
 * <li>/api/** REST for people (HTTP Basic, stateless); /api/admin/** needs ADMIN</li>
 * <li>everything else: the web UI (form login, session, CSRF); /admin/** needs ADMIN</li>
 * </ol>
 * Users live in the {@code users} table (see UserAccountService). Roles: VIEWER reads; TECHNICIAN sees assigned
 * machines and completes assigned work; ENGINEER registers machines, uploads, investigates, reviews; ADMIN manages
 * users, factories, gateways and assignments and sees ops endpoints. Which machines a person sees is decided by
 * {@link com.basintelligence.users.AccessScope} (factory and assignments), enforced in the services.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String CSP = "default-src 'self'; img-src 'self' data:; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; form-action 'self'; base-uri 'self'";

    @Bean
    @Order(1)
    SecurityFilterChain edgeChain(HttpSecurity http, BasProperties props, EdgeGatewayService gateways, AuditService audit)
            throws Exception {
        return machineChain(http.securityMatcher("/api/edge/**"),
                new EdgeAuthenticationFilter(props.security().edgeApiKey(), gateways, audit), "EDGE");
    }

    @Bean
    @Order(2)
    SecurityFilterChain agentChain(HttpSecurity http, BasProperties props) throws Exception {
        return machineChain(http.securityMatcher("/mcp", "/mcp/**"),
                new ApiKeyAuthenticationFilter(props.security().agentApiKey(), "kiro-agent", "AGENT"), "AGENT");
    }

    private static SecurityFilterChain machineChain(HttpSecurity http, jakarta.servlet.Filter filter, String role)
            throws Exception {
        return http
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                // The original request was authenticated by the key. MCP streams SSE responses through an async
                // dispatch, and the stateless chain must not re-authorise that dispatch without credentials.
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .anyRequest().hasRole(role))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /** Verified Basic credentials are remembered for 5 minutes so bursts of API calls do not each pay for BCrypt. */
    @Bean
    CachingBasicAuthenticationProvider basicAuthenticationProvider(UserAccountService users, PasswordEncoder encoder,
            java.time.Clock clock) {
        var dao = new org.springframework.security.authentication.dao.DaoAuthenticationProvider(users);
        dao.setPasswordEncoder(encoder);
        dao.setUserDetailsPasswordService(users); // re-hashes legacy hashes in the current format on login
        return new CachingBasicAuthenticationProvider(dao, users, java.time.Duration.ofMinutes(5), 1_000, clock);
    }

    @Bean
    @Order(3)
    SecurityFilterChain restChain(HttpSecurity http, CachingBasicAuthenticationProvider basicAuth, AuditService audit)
            throws Exception {
        return http
                .securityMatcher("/api/**")
                .authenticationProvider(basicAuth)
                // Stateless HTTP Basic: no cookies are issued, so CSRF does not apply.
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().hasRole("VIEWER"))
                .exceptionHandling(e -> e.accessDeniedHandler(new AuditingAccessDeniedHandler(audit)))
                .build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain webChain(HttpSecurity http, SessionRegistry sessions, AuditService audit) throws Exception {
        return http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/login", "/webjars/**", "/css/**", "/js/**", "/favicon.ico", "/error").permitAll()
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().hasRole("VIEWER"))
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/", false).permitAll())
                .logout(l -> l.logoutSuccessUrl("/login?logout"))
                // Session fixation protection is on by default; the registry lets administrators end sessions.
                .sessionManagement(s -> s.maximumSessions(-1).sessionRegistry(sessions).expiredUrl("/login?expired"))
                .exceptionHandling(e -> e.accessDeniedHandler(new AuditingAccessDeniedHandler(audit)))
                .headers(h -> h
                        .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
                .build();
    }

    /**
     * BCrypt (cost 10) with the algorithm recorded in each hash ({@code {bcrypt}...}), so the algorithm or cost can be
     * changed later without invalidating stored passwords. Hashes created before the prefix existed are plain BCrypt
     * and still match; they are rewritten with the prefix at the user's next login.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", bcrypt));
        encoder.setDefaultPasswordEncoderForMatches(bcrypt);
        return encoder;
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Tells the session registry when sessions end. */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }
}

package csd.tripsense.auth;

import csd.tripsense.audit.AuditService;
import csd.tripsense.user.User;
import csd.tripsense.user.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Session-based authentication using Spring Security's built-in form login and logout.
 * Accounts are loaded by UserDetailsServiceImpl; passwords are checked against BCrypt hashes.
 *
 * Authorization has two layers (E1-07): the URL rule below sends /api/admin/** to admins only, and
 * AdminService repeats the check on its methods (@EnableMethodSecurity), so one wrong route
 * cannot expose an admin operation.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** Remembers who is logged in where, so an admin action can end someone's sessions. */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();   // removes sessions from the registry when they end
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry sessionRegistry,
                                                   AuditService audit, UserRepository users) throws Exception {
        // CSRF: the token lives in the readable XSRF-TOKEN cookie; the frontend echoes it back in the
        // X-XSRF-TOKEN header on every POST/PATCH/DELETE. Null attribute name = load the token eagerly,
        // so the cookie is set on the very first response (e.g. loading the page).
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler))

            .sessionManagement(s -> s
                .maximumSessions(-1)                       // -1 = no limit; this only turns on tracking
                .sessionRegistry(sessionRegistry)
                .expiredSessionStrategy(event ->
                    event.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED)))

            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/user/register").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .requestMatchers("/error").permitAll()   // else error bodies (409, 400...) turn into 401
                .requestMatchers("/", "/index.html", "/js/**", "/css/**", "/favicon.ico").permitAll()   // the frontend has to load before anyone logs in
                .requestMatchers("/api/admin/**").hasRole("ADMIN")   // USER -> 403, not logged in -> 401
                .anyRequest().authenticated()
            )

            // POST /api/auth/login  (form fields: email, password)  ->  200 + JSESSIONID cookie
            .formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .successHandler((request, response, authentication) -> {
                    if (authentication.getPrincipal() instanceof CurrentUser user) {
                        audit.record(AuditService.LOGIN, user.getId(), user.getEmail(), user.getId(), null);
                    }
                    String role = authentication.getAuthorities().stream()
                            .map(GrantedAuthority::getAuthority)
                            .filter(a -> a.startsWith("ROLE_"))
                            .map(a -> a.substring("ROLE_".length()))
                            .findFirst().orElse("USER");
                    response.setStatus(HttpServletResponse.SC_OK);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"role\":\"" + role + "\"}");
                })
                .failureHandler((request, response, exception) -> {
                    // Same answer for unknown email, wrong password and disabled account, so the response
                    // reveals nothing. The attempted PASSWORD is never logged or stored (E7-09).
                    log.warn("Failed login attempt");
                    try {
                        String attempted = request.getParameter("email");
                        // Remember which real account the attempt was aimed at, so an admin reviewing that
                        // user sees it (the person trying is unknown, so they are not the "actor").
                        Long aimedAt = (attempted == null || attempted.isBlank()) ? null
                                : users.findByEmailIgnoreCase(attempted.trim()).map(User::getId).orElse(null);
                        audit.record(AuditService.LOGIN_FAILED, null, attempted, aimedAt,
                                exception instanceof DisabledException ? "account is disabled" : null);
                    } catch (RuntimeException e) {
                        log.error("Could not audit a failed login", e);   // never let this break the 401 reply
                    }
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"message\":\"Invalid email or password\"}");
                })
                .permitAll()
            )

            // POST /api/auth/logout  ->  204, session invalidated, cookie cleared
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .addLogoutHandler((request, response, authentication) -> {
                    if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
                        audit.record(AuditService.LOGOUT, user.getId(), user.getEmail(), user.getId(), null);
                    }
                })
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            )

            // Not logged in -> 401 (the default would redirect to a login page)
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }
}
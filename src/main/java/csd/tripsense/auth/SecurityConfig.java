package csd.tripsense.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;

/**
 * Session-based authentication using Spring Security's built-in form login and logout.
 * Accounts are loaded by UserDetailsServiceImpl; passwords are checked against BCrypt hashes.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // NOTE: CSRF protection is off so plain curl/Swagger clients can POST. With cookie sessions
            // a browser front end (E8-01) should turn it back on - see the notes that came with this code.
            .csrf(AbstractHttpConfigurer::disable)

            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/user/register").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .requestMatchers("/error").permitAll()   // else error bodies (409, 400...) turn into 401
                // Everything else needs a logged-in session. To keep teammates' endpoints open while
                // they integrate, temporarily change this one line to .permitAll().
                .anyRequest().authenticated()
            )

            // POST /api/user/login  (form fields: email, password)  ->  200 + JSESSIONID cookie
            .formLogin(form -> form
                .loginProcessingUrl("/api/user/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .successHandler((request, response, authentication) -> {
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
                    // Same answer for unknown email and wrong password. Never log the attempted password (E7-09).
                    log.warn("Failed login attempt");
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"message\":\"Invalid email or password\"}");
                })
                .permitAll()
            )

            // POST /api/user/logout  ->  204, session invalidated, cookie cleared
            .logout(logout -> logout
                .logoutUrl("/api/user/logout")
                .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            )

            // Not logged in -> 401 (the default would redirect to a login page)
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }
}

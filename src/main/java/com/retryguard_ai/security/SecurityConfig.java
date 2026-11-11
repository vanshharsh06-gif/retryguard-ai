
package com.retryguard_ai.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(
                                CookieCsrfTokenRepository.withHttpOnlyFalse()
                        )
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers("/api/webhooks/paypal")
                )

                // Eagerly resolve the CSRF token so the XSRF-TOKEN cookie is
                // written on the first response. Without this, the deferred
                // CsrfTokenRequestAttributeHandler only writes the cookie when
                // the token is accessed, and the browser dashboard's first
                // mutating request would be rejected with 403.
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)

                .cors(cors -> cors
                        .configurationSource(corsConfigurationSource())
                )

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/error").permitAll()

                        .requestMatchers(HttpMethod.GET, "/api/orders")
                        .permitAll()

                        // Allow PayPal browser return/cancel redirects
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/payment-attempts/*/paypal/return",
                                "/api/payment-attempts/*/paypal/cancel"
                        ).permitAll()

                        .requestMatchers("/api/webhooks/paypal").permitAll()

                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/info"
                        ).permitAll()

                        .anyRequest().authenticated()
                )

                // Form-based browser login
                .formLogin(form -> form
                        .defaultSuccessUrl("/", true)
                        .permitAll()
                )

                // Preserve HTTP Basic for API clients
                .httpBasic(basic -> basic
                        .realmName("RetryGuard AI")
                )

                // API requests receive 401 instead of login redirects
                .exceptionHandling(exception -> exception
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                request -> request.getServletPath().startsWith("/api/")
                        )
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration = new CorsConfiguration();

        String allowedOrigins =
                System.getenv("RETRYGUARD_CORS_ALLOWED_ORIGINS");

        Set<String> patterns = new LinkedHashSet<>();
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            Arrays.stream(allowedOrigins.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(patterns::add);
        } else {
            // Sensible local-demo defaults: localhost for the IntelliJ run,
            // and the configured ngrok public base URL for PayPal redirects.
            patterns.add("http://localhost:*");
            patterns.add("https://localhost:*");
            patterns.add("https://*.ngrok-free.dev");
            patterns.add("https://*.ngrok-free.app");
            patterns.add("https://*.ngrok.io");

            String publicBaseUrl = System.getenv("PAYPAL_PUBLIC_BASE_URL");
            if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
                patterns.add(publicBaseUrl.trim());
            }
        }

        configuration.setAllowedOriginPatterns(new ArrayList<>(patterns));

        configuration.setAllowedMethods(
                List.of("GET", "POST", "PUT", "DELETE", "OPTIONS")
        );

        configuration.setAllowedHeaders(
                List.of(
                        "Authorization",
                        "Content-Type",
                        "PAYPAL-AUTH-ALGO",
                        "PAYPAL-CERT-URL",
                        "PAYPAL-TRANSMISSION-ID",
                        "PAYPAL-TRANSMISSION-SIG",
                        "PAYPAL-TRANSMISSION-TIME",
                        "X-XSRF-TOKEN",
                        "X-CSRF-TOKEN"
                )
        );

        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    /**
     * Forces CSRF token resolution on every request so the XSRF-TOKEN cookie
     * is emitted on the response. Without eager resolution, the deferred
     * {@link CsrfTokenRequestAttributeHandler} only writes the cookie when the
     * token is actually accessed during request handling, which caused the
     * browser dashboard's first mutating request to fail with 403.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                         HttpServletResponse response,
                                         FilterChain filterChain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                // Access the token to trigger the deferred CookieCsrfTokenRepository
                // to write the XSRF-TOKEN cookie on the response.
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}

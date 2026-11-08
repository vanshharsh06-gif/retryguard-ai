package com.retryguard_ai.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security configuration for RetryGuard AI.
 *
 * Security design:
 * - Permitted without authentication:
 *   * GET /api/orders (read-only order listing used by the dashboard)
 *   * /api/webhooks/paypal (PayPal webhook — protected by mandatory signature verification)
 *   * /error, /actuator/health, /actuator/info
 * - All other HTTP methods on /api/orders and all other endpoints require authentication.
 * - Authentication is HTTP Basic using credentials from environment variables.
 * - CSRF uses cookie-based token storage so the dashboard can submit state-changing
 *   requests (POST forms) after obtaining the CSRF token cookie. PayPal webhooks are
 *   excluded from CSRF since PayPal cannot present a CSRF token.
 * - The PayPal webhook signature verification is NEVER bypassed.
 * - CORS is restricted to configured allowed origins.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/webhooks/paypal"))
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                        // Read-only GET order listing is public (dashboard)
                        .requestMatchers(HttpMethod.GET, "/api/orders").permitAll()
                        // Webhook endpoint is public but protected by signature verification
                        .requestMatchers("/api/webhooks/paypal").permitAll()
                        .requestMatchers("/error", "/actuator/health", "/actuator/info").permitAll()
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .httpBasic(httpBasic -> httpBasic
                        .realmName("RetryGuard AI")
                );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        String allowedOrigins = System.getenv("RETRYGUARD_CORS_ALLOWED_ORIGINS");
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            configuration.setAllowedOriginPatterns(List.of(allowedOrigins.split(",")));
        } else {
            // Development-only fallback: same-origin. Production must set
            // RETRYGUARD_CORS_ALLOWED_ORIGINS explicitly.
            configuration.setAllowedOriginPatterns(List.of("http://localhost:*"));
        }
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type",
                "PAYPAL-AUTH-ALGO", "PAYPAL-CERT-URL", "PAYPAL-TRANSMISSION-ID",
                "PAYPAL-TRANSMISSION-SIG", "PAYPAL-TRANSMISSION-TIME",
                "X-XSRF-TOKEN", "X-CSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

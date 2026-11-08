package com.retryguard_ai.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.util.List;

/**
 * Provides an in-memory user for HTTP Basic authentication sourced from
 * environment variables (RETRYGUARD_API_USER / RETRYGUARD_API_PASSWORD).
 * No default password is provided. If credentials are missing, no user is
 * registered and all authenticated endpoints reject requests.
 *
 * Passwords are stored using BCrypt (via {@link BCryptPasswordEncoder}), not
 * plaintext or {noop}.
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityUserConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(Environment environment,
                                                 SecurityProperties securityProperties,
                                                 PasswordEncoder passwordEncoder) {
        String user = securityProperties.user() != null ? securityProperties.user()
                : environment.getProperty("RETRYGUARD_API_USER");
        String password = securityProperties.password() != null ? securityProperties.password()
                : environment.getProperty("RETRYGUARD_API_PASSWORD");

        if (user == null || user.isBlank() || password == null || password.isBlank()) {
            return new InMemoryUserDetailsManager(List.of());
        }

        UserDetails userDetails = User.withUsername(user)
                .password(passwordEncoder.encode(password))
                .roles("API_USER")
                .build();
        return new InMemoryUserDetailsManager(List.of(userDetails));
    }
}

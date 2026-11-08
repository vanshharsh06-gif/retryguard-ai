package com.retryguard_ai.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Security credentials sourced exclusively from environment variables.
 * No defaults are provided for the password; production deployments must set
 * RETRYGUARD_API_USER and RETRYGUARD_API_PASSWORD.
 *
 * This is a @ConfigurationProperties-only type (not a @Configuration class)
 * so it can be bound and injected without CGLIB enhancement issues.
 */
@ConfigurationProperties(prefix = "retryguard.security")
public record SecurityProperties(
        String user,
        String password
) {
}

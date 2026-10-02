package com.shipyard.tracker.security;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * Entry point for the auth layer. Pick the mode with app.authn.type:
 * <ul>
 *   <li><b>basic</b> (default): sign in with a form using the test users in auth-defaults.properties.</li>
 *   <li><b>oauth2</b>: sign in through your identity provider (spring.security.oauth2.client.*).</li>
 *   <li><b>none</b>: no authentication, exactly like the original POC.</li>
 * </ul>
 * auth-defaults.properties holds fallback values; anything in application.properties wins.
 */
@Configuration
@EnableWebSecurity
@PropertySource("classpath:auth-defaults.properties")
public class SecurityConfig {

    private static final Set<String> MODES = Set.of("basic", "oauth2", "none");

    public SecurityConfig(@Value("${app.authn.type:basic}") String mode) {
        if (!MODES.contains(mode)) {
            throw new IllegalStateException("app.authn.type must be one of " + MODES + " but was '" + mode + "'");
        }
    }
}

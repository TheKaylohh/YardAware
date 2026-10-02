package com.shipyard.tracker.security;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.authorization.SpringAuthorizationEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * Entry point for the auth layer. Pick the mode with app.authn.type:
 * <ul>
 *   <li><b>oauth2</b> (default): sign in through your identity provider (spring.security.oauth2.client.*).</li>
 *   <li><b>basic</b>: local test users from application-dev.properties. Development only.</li>
 *   <li><b>none</b>: no authentication. Development only.</li>
 * </ul>
 * ProductionSafetyGuard refuses basic and none unless app.security.dev-mode=true.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Set<String> MODES = Set.of("basic", "oauth2", "none");

    public SecurityConfig(@Value("${app.authn.type:oauth2}") String mode, ProductionSafetyGuard guard) {
        if (!MODES.contains(mode)) {
            throw new IllegalStateException("app.authn.type must be one of " + MODES + " but was '" + mode + "'");
        }
    }

    /** Publishes AuthorizationDeniedEvent so SecurityAuditListener can record refused requests. */
    @Bean
    public AuthorizationEventPublisher authorizationEventPublisher(ApplicationEventPublisher publisher) {
        return new SpringAuthorizationEventPublisher(publisher);
    }
}

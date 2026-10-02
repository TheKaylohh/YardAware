package com.shipyard.tracker.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/** app.authn.type=none: everything is open, as in the original POC. Never use this outside your own machine. */
@Configuration
@ConditionalOnProperty(name = "app.authn.type", havingValue = "none")
public class NoAuthSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(NoAuthSecurityConfig.class);

    @Bean
    public SecurityFilterChain openChain(HttpSecurity http) throws Exception {
        log.warn("Authentication is OFF (app.authn.type=none). Everyone can read and change everything.");
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }
}

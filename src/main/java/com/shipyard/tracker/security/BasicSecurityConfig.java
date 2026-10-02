package com.shipyard.tracker.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;

/** Local mode: username and password from configuration, Spring's built-in login page. */
@Configuration
@ConditionalOnProperty(name = "app.authn.type", havingValue = "basic", matchIfMissing = true)
@EnableConfigurationProperties(BasicAuthProps.class)
public class BasicSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(BasicSecurityConfig.class);

    @Bean
    public UserDetailsService userDetailsService(BasicAuthProps props) {
        log.warn("Authentication mode 'basic' with {} configured test user(s). For local testing only.", props.getUsers().size());
        return new BasicUserDetailsService(props.getUsers());
    }

    @Bean
    @Order(1)
    public SecurityFilterChain basicApiChain(HttpSecurity http) throws Exception {
        return SecuritySupport.apiChain(http, true);
    }

    @Bean
    @Order(2)
    public SecurityFilterChain basicWebChain(HttpSecurity http,
                                             @Value("${spring.h2.console.path:/h2-console}") String h2Path) throws Exception {
        SecuritySupport.configureWeb(http, h2Path);
        http.formLogin(Customizer.withDefaults());
        return http.build();
    }
}

package com.shipyard.tracker.security;

import java.time.Clock;
import java.time.Duration;
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

/** Local mode (development only, see ProductionSafetyGuard): username and password from configuration, Spring's built-in login page. */
@Configuration
@ConditionalOnProperty(name = "app.authn.type", havingValue = "basic", matchIfMissing = false)
@EnableConfigurationProperties(BasicAuthProps.class)
public class BasicSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(BasicSecurityConfig.class);

    @Bean
    public LoginAttemptService loginAttemptService(
            @Value("${app.security.login.max-failures-per-user:5}") int maxPerUser,
            @Value("${app.security.login.max-failures-per-ip:50}") int maxPerIp,
            @Value("${app.security.login.lockout-minutes:15}") int lockoutMinutes) {
        return new LoginAttemptService(maxPerUser, maxPerIp, Duration.ofMinutes(lockoutMinutes), Clock.systemUTC());
    }

    @Bean
    public UserDetailsService userDetailsService(BasicAuthProps props, LoginAttemptService attempts) {
        log.warn("Authentication mode 'basic' with {} configured test user(s). For local testing only.", props.getUsers().size());
        return new BasicUserDetailsService(props.getUsers(), attempts);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain basicApiChain(HttpSecurity http, SecuritySettings settings) throws Exception {
        return SecuritySupport.apiChain(http, true, settings);
    }

    @Bean
    @Order(2)
    public SecurityFilterChain basicWebChain(HttpSecurity http, SecuritySettings settings,
                                             @Value("${spring.h2.console.path:/h2-console}") String h2Path) throws Exception {
        SecuritySupport.configureWeb(http, h2Path, settings);
        http.formLogin(Customizer.withDefaults());
        return http.build();
    }
}

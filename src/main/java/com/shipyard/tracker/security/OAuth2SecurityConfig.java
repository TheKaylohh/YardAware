package com.shipyard.tracker.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth2 / OpenID Connect mode. Client registrations come from the standard Spring properties
 * (spring.security.oauth2.client.registration.* and .provider.*). Roles come from the RolesLookupService bean;
 * the default reads app.authz.oauth2.memory.roles.*, and your own implementation can replace it
 * (declare it with app.authz.oauth2.type=eldap, like the template does).
 */
@Configuration
@ConditionalOnProperty(name = "app.authn.type", havingValue = "oauth2", matchIfMissing = true)
@EnableConfigurationProperties(UserRolesProps.class)
public class OAuth2SecurityConfig {

    @Bean
    @ConditionalOnProperty(name = "app.authz.oauth2.type", havingValue = "memory", matchIfMissing = true)
    public RolesLookupService inMemoryRolesLookupService(UserRolesProps props) {
        return new InMemoryRolesLookupService(props);
    }

    @Bean
    @Order(1)
    public SecurityFilterChain oauth2ApiChain(HttpSecurity http, SecuritySettings settings) throws Exception {
        return SecuritySupport.apiChain(http, false, settings);
    }

    @Bean
    @Order(2)
    public SecurityFilterChain oauth2WebChain(HttpSecurity http,
                                              SecuritySettings settings,
                                              RolesLookupService roles,
                                              @Value("${app.authz.oauth2.username-claim:preferred_username}") String usernameClaim,
                                              @Value("${app.authz.oauth2.trust-unverified-email:false}") boolean trustUnverifiedEmail,
                                              @Value("${spring.h2.console.path:/h2-console}") String h2Path) throws Exception {
        SecuritySupport.configureWeb(http, h2Path, settings);
        http.oauth2Login(login -> login.userInfoEndpoint(info -> info
                .oidcUserService(OAuth2RoleMapping.oidc(roles, usernameClaim, trustUnverifiedEmail))
                .userService(OAuth2RoleMapping.plain(roles, trustUnverifiedEmail))));
        return http.build();
    }
}

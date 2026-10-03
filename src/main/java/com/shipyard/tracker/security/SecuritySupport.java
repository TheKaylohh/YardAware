package com.shipyard.tracker.security;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The two filter chains shared by the basic and OAuth2 modes.
 * <ul>
 *   <li><b>API chain</b> (/api/**): answers 401 instead of redirecting. Reading needs VIEWER/EDITOR/ADMIN
 *       (or just a sign-in when app.authz.require-viewer-role=false); everything else needs EDITOR or ADMIN.
 *       CSRF token travels in the XSRF-TOKEN cookie / X-XSRF-TOKEN header.</li>
 *   <li><b>Web chain</b> (everything else): the site itself, login/logout, the H2 console (ADMIN only, dev only).</li>
 * </ul>
 * Both chains share the same session, so signing in through one authenticates the other.
 */
final class SecuritySupport {

    private static final String[] READ_ROLES = {AppRoles.VIEWER, AppRoles.EDITOR, AppRoles.ADMIN};
    private static final long ONE_YEAR_SECONDS = 31_536_000L;

    private SecuritySupport() {
    }

    static SecurityFilterChain apiChain(HttpSecurity http, boolean allowHttpBasic, SecuritySettings settings) throws Exception {
        // Requests that carry an Authorization: Basic header can't be forged by another website, so they skip CSRF.
        RequestMatcher basicHeader = request -> {
            String header = request.getHeader("Authorization");
            return header != null && header.regionMatches(true, 0, "Basic ", 0, 6);
        };

        http.securityMatcher(antMatcher("/api/**"))
                .authorizeHttpRequests(auth -> {
                    readAccess(auth.requestMatchers(antMatcher(HttpMethod.GET, "/api/**"), antMatcher(HttpMethod.HEAD, "/api/**")), settings);
                    // Creating a ship generates hundreds of records, so it is not an everyday editor action.
                    auth.requestMatchers(antMatcher(HttpMethod.POST, "/api/hulls")).hasRole(AppRoles.ADMIN);
                    auth.anyRequest().hasAnyRole(AppRoles.EDITOR, AppRoles.ADMIN);
                })
                .csrf(csrf -> {
                    csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse());
                    csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
                    if (allowHttpBasic) {
                        csrf.ignoringRequestMatchers(basicHeader);
                    }
                })
                .headers(headers -> commonHeaders(headers))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        if (allowHttpBasic) {
            // Handy for curl and tests. No WWW-Authenticate challenge, so browsers never pop up a native login box.
            http.httpBasic(basic -> basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        }
        return http.build();
    }

    /** Common settings for the web chain. The caller adds the login mechanism (form login or OAuth2). */
    static void configureWeb(HttpSecurity http, String h2ConsolePath, SecuritySettings settings) throws Exception {
        RequestMatcher h2Console = antMatcher(h2ConsolePath + "/**");
        RequestMatcher notH2Console = new NegatedRequestMatcher(h2Console);
        http.authorizeHttpRequests(auth -> {
                    // Error pages must stay reachable or Spring Security hides the real error behind a login redirect.
                    auth.requestMatchers(antMatcher("/error")).permitAll();
                    // Health probes for the load balancer / orchestrator. Only status is exposed (no details).
                    auth.requestMatchers(antMatcher("/actuator/health"), antMatcher("/actuator/health/**")).permitAll();
                    auth.requestMatchers(h2Console).hasRole(AppRoles.ADMIN);
                    readAccess(auth.anyRequest(), settings);
                })
                .csrf(csrf -> {
                    csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse());
                    csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
                    if (settings.h2ConsoleEnabled()) {
                        csrf.ignoringRequestMatchers(h2Console);
                    }
                })
                .headers(headers -> {
                    commonHeaders(headers);
                    // The app may never be framed. Only the H2 console (dev only) frames itself.
                    headers.frameOptions(frame -> frame.disable());
                    headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(notH2Console,
                            new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.DENY)));
                    if (settings.h2ConsoleEnabled()) {
                        headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(h2Console,
                                new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.SAMEORIGIN)));
                    }
                    if (!settings.contentSecurityPolicy().isEmpty()) {
                        headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(notH2Console,
                                new ContentSecurityPolicyHeaderWriter(settings.contentSecurityPolicy())));
                    }
                })
                .logout(logout -> logout.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);
    }

    private static void readAccess(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizedUrl url, SecuritySettings settings) {
        if (settings.requireViewerRole()) {
            url.hasAnyRole(READ_ROLES);
        } else {
            url.authenticated();
        }
    }

    private static void commonHeaders(HeadersConfigurer<HttpSecurity> headers) {
        // HSTS is only sent on HTTPS requests (server.forward-headers-strategy=framework makes proxied requests count).
        headers.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(ONE_YEAR_SECONDS));
        headers.referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
        headers.addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                "camera=(), microphone=(), geolocation=(), payment=(), usb=()"));
    }
}

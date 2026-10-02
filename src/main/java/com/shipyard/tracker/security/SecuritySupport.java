package com.shipyard.tracker.security;

import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The two filter chains shared by the basic and OAuth2 modes.
 * <ul>
 *   <li><b>API chain</b> (/api/**): answers 401 instead of redirecting. Reading needs any signed-in user,
 *       everything else needs EDITOR or ADMIN. CSRF token travels in the XSRF-TOKEN cookie / X-XSRF-TOKEN header.</li>
 *   <li><b>Web chain</b> (everything else): the site itself, login/logout, the H2 console (ADMIN only).</li>
 * </ul>
 * Both chains share the same session, so signing in through one authenticates the other.
 */
final class SecuritySupport {

    private SecuritySupport() {
    }

    static SecurityFilterChain apiChain(HttpSecurity http, boolean allowHttpBasic) throws Exception {
        // Requests that carry an Authorization: Basic header can't be forged by another website, so they skip CSRF.
        RequestMatcher basicHeader = request -> {
            String header = request.getHeader("Authorization");
            return header != null && header.regionMatches(true, 0, "Basic ", 0, 6);
        };

        http.securityMatcher(antMatcher("/api/**"))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(antMatcher(HttpMethod.GET, "/api/**"), antMatcher(HttpMethod.HEAD, "/api/**")).authenticated()
                        .anyRequest().hasAnyRole(AppRoles.EDITOR, AppRoles.ADMIN))
                .csrf(csrf -> {
                    csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse());
                    csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
                    if (allowHttpBasic) {
                        csrf.ignoringRequestMatchers(basicHeader);
                    }
                })
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        if (allowHttpBasic) {
            // Handy for curl and tests. No WWW-Authenticate challenge, so browsers never pop up a native login box.
            http.httpBasic(basic -> basic.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        }
        return http.build();
    }

    /** Common settings for the web chain. The caller adds the login mechanism (form login or OAuth2). */
    static void configureWeb(HttpSecurity http, String h2ConsolePath) throws Exception {
        RequestMatcher h2Console = antMatcher(h2ConsolePath + "/**");
        http.authorizeHttpRequests(auth -> auth
                        // Error pages must stay reachable or Spring Security hides the real error message behind a login redirect.
                        .requestMatchers(antMatcher("/error")).permitAll()
                        .requestMatchers(h2Console).hasRole(AppRoles.ADMIN)
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(h2Console))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .logout(logout -> logout.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler()))
                .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class);
    }
}

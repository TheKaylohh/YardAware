package com.shipyard.tracker.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Roles are worked out at sign-in and kept in the session. This filter works them out again on every request (cached for
 * a few seconds), so a role granted, changed or withdrawn on the Admin page takes effect on the person's next request.
 * Only applies to people signed in through the identity provider.
 */
public class RoleRefreshFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RoleRefreshFilter.class);

    private final EffectiveRoles roles;
    private final boolean trustUnverifiedEmail;

    public RoleRefreshFilter(EffectiveRoles roles, boolean trustUnverifiedEmail) {
        this.roles = roles;
        this.trustUnverifiedEmail = trustUnverifiedEmail;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            refresh();
        } catch (RuntimeException e) {
            // If the lookup fails, keep the roles from sign-in rather than breaking the request.
            log.warn("Could not refresh roles: {}", e.toString());
        }
        chain.doFilter(request, response);
    }

    private void refresh() {
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication auth = context.getAuthentication();
        if (!(auth instanceof OAuth2AuthenticationToken token) || !token.isAuthenticated()) {
            return;
        }
        OAuth2User principal = token.getPrincipal();
        Object email = OAuth2RoleMapping.trusted(principal.getAttributes().get("email"),
                principal.getAttributes().get("email_verified"), trustUnverifiedEmail);
        Set<String> wanted = roles.resolve(token.getName(), email == null ? null : email.toString());

        Set<String> current = new HashSet<>();
        Set<GrantedAuthority> next = new LinkedHashSet<>();
        for (GrantedAuthority authority : token.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                current.add(name.substring("ROLE_".length()));
            } else {
                next.add(authority);
            }
        }
        if (current.equals(wanted)) {
            return;
        }
        for (String role : wanted) {
            next.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
        OAuth2AuthenticationToken updated =
                new OAuth2AuthenticationToken(principal, next, token.getAuthorizedClientRegistrationId());
        updated.setDetails(token.getDetails());
        context.setAuthentication(updated);
    }
}

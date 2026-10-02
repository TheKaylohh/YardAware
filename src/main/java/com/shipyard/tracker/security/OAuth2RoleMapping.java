package com.shipyard.tracker.security;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Adds ROLE_EDITOR / ROLE_ADMIN to the user at sign-in time. The roles then live in the session with the
 * principal, so every chain sees them and the role source (for example eLDAP) is only called once per login.
 */
final class OAuth2RoleMapping {

    private OAuth2RoleMapping() {
    }

    static OAuth2UserService<OidcUserRequest, OidcUser> oidc(RolesLookupService roles, String usernameClaim) {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser loaded = delegate.loadUser(request);
            Map<String, Object> claims = loaded.getClaims();
            // The claim that identifies the user in the Activity log; "sub" is always present as a fallback.
            String nameKey = claims.get(usernameClaim) != null ? usernameClaim : IdTokenClaimNames.SUB;
            Set<GrantedAuthority> authorities = new LinkedHashSet<>(loaded.getAuthorities());
            addRoles(authorities, roles, String.valueOf(claims.get(nameKey)), claims.get("email"));
            return new DefaultOidcUser(authorities, loaded.getIdToken(), loaded.getUserInfo(), nameKey);
        };
    }

    /** For providers that speak plain OAuth2 rather than OpenID Connect. */
    static OAuth2UserService<OAuth2UserRequest, OAuth2User> plain(RolesLookupService roles) {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        return request -> {
            OAuth2User loaded = delegate.loadUser(request);
            String nameKey = request.getClientRegistration().getProviderDetails()
                    .getUserInfoEndpoint().getUserNameAttributeName();
            Set<GrantedAuthority> authorities = new LinkedHashSet<>(loaded.getAuthorities());
            addRoles(authorities, roles, loaded.getName(), loaded.getAttributes().get("email"));
            return new DefaultOAuth2User(authorities, loaded.getAttributes(), nameKey);
        };
    }

    /** Looks the user up by username and by e-mail, so either can be listed in the role configuration. */
    private static void addRoles(Set<GrantedAuthority> authorities, RolesLookupService roles, String username, Object email) {
        Set<String> found = new HashSet<>(roles.getRolesForUser(username));
        if (email != null) {
            found.addAll(roles.getRolesForUser(email.toString()));
        }
        for (String role : found) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)));
        }
    }
}

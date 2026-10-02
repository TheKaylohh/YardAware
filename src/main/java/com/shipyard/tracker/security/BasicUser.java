package com.shipyard.tracker.security;

import java.util.Collection;
import java.util.Locale;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** A Spring Security user that also carries a display name and e-mail. */
public class BasicUser extends User {
    private static final long serialVersionUID = 1L;

    private final String name;
    private final String email;

    public BasicUser(String username, String password, Collection<String> roles, String name, String email) {
        this(username, password, roles, name, email, true);
    }

    /** accountNonLocked=false makes Spring refuse the sign-in before the password is even checked. */
    public BasicUser(String username, String password, Collection<String> roles, String name, String email,
                     boolean accountNonLocked) {
        super(username, password, true, true, true, accountNonLocked, roles.stream()
                .map(role -> "ROLE_" + role.trim().toUpperCase(Locale.ROOT))
                .map(SimpleGrantedAuthority::new)
                .toList());
        this.name = name;
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }
}

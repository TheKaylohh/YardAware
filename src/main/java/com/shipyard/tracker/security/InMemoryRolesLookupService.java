package com.shipyard.tracker.security;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Roles taken from configuration. Matching ignores case, so usernames and e-mail addresses both work. */
public class InMemoryRolesLookupService implements RolesLookupService {

    private final UserRolesProps props;

    public InMemoryRolesLookupService(UserRolesProps props) {
        this.props = props;
    }

    @Override
    public Set<String> getRolesForUser(String username) {
        Set<String> roles = new HashSet<>();
        if (username == null || username.isBlank()) {
            return roles;
        }
        String key = username.trim().toLowerCase(Locale.ROOT);
        if (listed(props.getAdmin(), key)) {
            roles.add(AppRoles.ADMIN);
        }
        if (listed(props.getEditor(), key)) {
            roles.add(AppRoles.EDITOR);
        }
        return roles;
    }

    private static boolean listed(List<String> names, String key) {
        return names != null && names.stream().anyMatch(n -> n != null && n.trim().toLowerCase(Locale.ROOT).equals(key));
    }
}

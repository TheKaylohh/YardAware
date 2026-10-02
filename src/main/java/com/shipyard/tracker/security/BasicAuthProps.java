package com.shipyard.tracker.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local test users for app.authn.type=basic. Passwords need an encoder prefix, e.g. {noop}secret or {bcrypt}$2a$...
 * <pre>
 * app.authn.basic.users[0].username=editor
 * app.authn.basic.users[0].password={noop}editor123
 * app.authn.basic.users[0].roles=EDITOR
 * </pre>
 */
@ConfigurationProperties(prefix = "app.authn.basic")
public class BasicAuthProps {
    private List<UserEntry> users = new ArrayList<>();

    public List<UserEntry> getUsers() { return users; }
    public void setUsers(List<UserEntry> users) { this.users = users; }

    public static class UserEntry {
        private String username;
        private String password;
        private String name;
        private String email;
        private List<String> roles = new ArrayList<>();

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles; }
    }
}

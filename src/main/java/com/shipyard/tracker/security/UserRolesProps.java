package com.shipyard.tracker.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.authz.oauth2.memory.roles.editor=alice,bob@corp.com  and  ...admin=carol */
@ConfigurationProperties(prefix = "app.authz.oauth2.memory.roles")
public class UserRolesProps {
    private List<String> admin = new ArrayList<>();
    private List<String> editor = new ArrayList<>();

    public List<String> getAdmin() { return admin; }
    public void setAdmin(List<String> admin) { this.admin = admin; }
    public List<String> getEditor() { return editor; }
    public void setEditor(List<String> editor) { this.editor = editor; }
}

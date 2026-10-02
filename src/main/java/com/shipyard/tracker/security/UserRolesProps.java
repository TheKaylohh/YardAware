package com.shipyard.tracker.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.authz.oauth2.memory.roles.viewer=...  .editor=alice,bob@corp.com  .admin=carol */
@ConfigurationProperties(prefix = "app.authz.oauth2.memory.roles")
public class UserRolesProps {
    private List<String> viewer = new ArrayList<>();
    private List<String> admin = new ArrayList<>();
    private List<String> editor = new ArrayList<>();

    public List<String> getViewer() { return viewer; }
    public void setViewer(List<String> viewer) { this.viewer = viewer; }
    public List<String> getAdmin() { return admin; }
    public void setAdmin(List<String> admin) { this.admin = admin; }
    public List<String> getEditor() { return editor; }
    public void setEditor(List<String> editor) { this.editor = editor; }
}

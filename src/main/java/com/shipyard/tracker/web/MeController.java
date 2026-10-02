package com.shipyard.tracker.web;

import com.shipyard.tracker.security.AuthUser;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the frontend who is signed in and whether to offer editing. The server still enforces it. */
@RestController
@RequestMapping("/api")
public class MeController {

    private final String authMode;

    public MeController(@Value("${app.authn.type:oauth2}") String authMode) {
        this.authMode = authMode;
    }

    @GetMapping("/me")
    public MeResponse me() {
        AuthUser user = AuthUser.from(SecurityContextHolder.getContext().getAuthentication());
        if (user == null) {
            // Authentication is off: behave like the original POC.
            return new MeResponse(null, null, null, List.of(), true, false, authMode);
        }
        return new MeResponse(user.username(), user.name(), user.email(), user.roles(), user.canEdit(), true, authMode);
    }

    public record MeResponse(String username, String name, String email, List<String> roles,
                             boolean canEdit, boolean authenticated, String authMode) {
    }
}

package com.shipyard.tracker.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in entry point for the frontend. This path requires authentication, so visiting it sends an
 * unauthenticated browser to the login page; afterwards it bounces the browser back to where it came from.
 * It also works when Apache serves the static files and only proxies the auth paths to this app.
 */
@RestController
public class AuthEntryController {

    @GetMapping("/auth/login")
    public ResponseEntity<Void> login(@RequestParam(name = "redirect_uri", required = false) String redirectUri) {
        return ResponseEntity.status(HttpStatus.FOUND).location(safeTarget(redirectUri)).build();
    }

    /** Only same-site paths are allowed, so this can't be abused as an open redirect. */
    static URI safeTarget(String candidate) {
        String target = "/";
        if (candidate != null && candidate.startsWith("/") && !candidate.startsWith("//")
                && !candidate.contains("\\") && !candidate.contains("\r") && !candidate.contains("\n")) {
            target = candidate;
        }
        try {
            URI uri = URI.create(target);
            // Belt and braces: whatever got through the checks above must still parse as a path on this site.
            if (uri.getScheme() != null || uri.getRawAuthority() != null || uri.getRawPath() == null
                    || !uri.getRawPath().startsWith("/") || target.length() > 2000) {
                return URI.create("/");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            return URI.create("/");
        }
    }
}

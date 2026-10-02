package com.shipyard.tracker.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes security events to the SECURITY_AUDIT logger: sign-ins, failed sign-ins and refused requests.
 * Route that logger to your central log store (SIEM) so the trail survives the server and can't be edited by app admins.
 * Also feeds LoginAttemptService when basic mode is on.
 */
@Component
public class SecurityAuditListener {

    private static final Logger audit = LoggerFactory.getLogger("SECURITY_AUDIT");

    private final LoginAttemptService attempts;

    public SecurityAuditListener(ObjectProvider<LoginAttemptService> attempts) {
        this.attempts = attempts.getIfAvailable();
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        Authentication auth = event.getAuthentication();
        audit.info("LOGIN_SUCCESS user={} ip={}", safe(auth.getName()), ip(auth));
        if (attempts != null) {
            attempts.succeeded(auth.getName());
        }
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        Authentication auth = event.getAuthentication();
        String ip = ip(auth);
        audit.warn("LOGIN_FAILURE user={} ip={} reason={}", safe(auth.getName()), ip,
                event.getException().getClass().getSimpleName());
        if (attempts != null) {
            attempts.failed(auth.getName(), ip);
        }
    }

    @EventListener
    public void onDenied(AuthorizationDeniedEvent<?> event) {
        Authentication auth = event.getAuthentication().get();
        HttpServletRequest request = currentRequest();
        if (auth == null || auth instanceof AnonymousAuthenticationToken) {
            return; // anonymous requests are just sent to sign in; not worth an audit line each
        }
        audit.warn("ACCESS_DENIED user={} ip={} request={} {}", safe(auth.getName()), ip(auth),
                request == null ? "?" : request.getMethod(), request == null ? "?" : safe(request.getRequestURI()));
    }

    /** Client address; correct behind a proxy when server.forward-headers-strategy=framework. */
    static String ip(Authentication auth) {
        if (auth != null && auth.getDetails() instanceof WebAuthenticationDetails details) {
            return details.getRemoteAddress();
        }
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getRemoteAddr();
    }

    static HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    /** Stops log forging: strips line breaks and control characters from user-supplied text. */
    static String safe(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.replaceAll("\\p{Cntrl}", "_");
        return clean.length() > 200 ? clean.substring(0, 200) + "..." : clean;
    }
}

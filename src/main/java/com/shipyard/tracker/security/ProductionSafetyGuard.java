package com.shipyard.tracker.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails closed: refuses to start when a development-only setting is switched on outside development.
 * Development is declared explicitly with app.security.dev-mode=true (set by application-dev.properties).
 */
@Component
public class ProductionSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyGuard.class);

    public ProductionSafetyGuard(Environment env) {
        boolean devMode = env.getProperty("app.security.dev-mode", Boolean.class, false);
        if (devMode && Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("app.security.dev-mode=true cannot be combined with the prod profile. "
                    + "Something is switching development settings on in a production run.");
        }
        List<String> problems = problems(env);
        if (problems.isEmpty()) {
            return;
        }
        if (devMode) {
            log.warn("DEVELOPMENT MODE. These settings would be refused in production: {}", problems);
            return;
        }
        throw new IllegalStateException("Refusing to start with unsafe settings (set them per environment, "
                + "or use the dev profile on your own machine): " + String.join("; ", problems));
    }

    static List<String> problems(Environment env) {
        List<String> problems = new ArrayList<>();

        String mode = env.getProperty("app.authn.type", "oauth2").trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("oauth2")) {
            problems.add("app.authn.type=" + mode + " (only oauth2 is allowed outside development)");
        }
        if (env.getProperty("spring.h2.console.enabled", Boolean.class, false)) {
            problems.add("spring.h2.console.enabled=true (the console can run code on the server)");
        }
        String url = env.getProperty("spring.datasource.url", "");
        if (url.isBlank()) {
            problems.add("spring.datasource.url is not set (Spring would silently start an in-memory database)");
        } else if (url.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:")) {
            problems.add("spring.datasource.url points at H2 (use PostgreSQL or another server database)");
        }
        String ddl = env.getProperty("spring.jpa.hibernate.ddl-auto", "none").trim().toLowerCase(Locale.ROOT);
        if (!ddl.equals("validate") && !ddl.equals("none")) {
            problems.add("spring.jpa.hibernate.ddl-auto=" + ddl + " (use validate and let Flyway change the schema)");
        }
        if (env.getProperty("shipyard.seed-demo-data", Boolean.class, false)) {
            problems.add("shipyard.seed-demo-data=true (would write fake records and fake actors into the audit log)");
        }
        if (!env.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            problems.add("server.servlet.session.cookie.secure is not true");
        }
        if (!env.getProperty("app.authz.require-viewer-role", Boolean.class, true)) {
            problems.add("app.authz.require-viewer-role=false (every account at the identity provider could read all data)");
        }
        if (env.getProperty("server.error.include-message", "never").trim().equalsIgnoreCase("always")
                || env.getProperty("server.error.include-stacktrace", "never").trim().equalsIgnoreCase("always")) {
            problems.add("server.error.include-message/include-stacktrace=always (leaks internals to clients)");
        }
        return problems;
    }
}

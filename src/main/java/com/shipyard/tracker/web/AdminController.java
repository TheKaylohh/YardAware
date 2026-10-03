package com.shipyard.tracker.web;

import com.shipyard.tracker.domain.AdminEvent;
import com.shipyard.tracker.domain.AppUser;
import com.shipyard.tracker.security.EffectiveRoles;
import com.shipyard.tracker.security.UserDirectory;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Behind the Admin page. Everything under /api/admin needs the ADMIN role (SecuritySupport), for reading as well.
 * Creating ships is {@code POST /api/hulls} in ApiController, which is admin only too.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record AdminUser(
            Long id,
            String username,
            String email,
            String displayName,
            String role,
            boolean enabled,
            List<String> configRoles,
            boolean pending,
            Instant createdAt,
            Instant lastLoginAt) {
    }

    /** {@code signInManaged} is true when people sign in through the identity provider, where these records apply. */
    public record UsersResponse(String authMode, boolean signInManaged, List<AdminUser> users) {
    }

    public record AddUserRequest(String identifier, String role) {
    }

    /** {@code role}: VIEWER, EDITOR, ADMIN or NONE; leave out to keep it. {@code enabled}: leave out to keep it. */
    public record UpdateUserRequest(String role, Boolean enabled) {
    }

    public record AdminEventDto(Long id, Instant timestamp, String actor, String action, String target, String detail) {
    }

    public record SystemInfo(String authMode, List<String> profiles, String java, long uptimeSeconds) {
    }

    private final UserDirectory directory;
    private final ObjectProvider<EffectiveRoles> effective;
    private final Environment environment;
    private final String authMode;

    public AdminController(UserDirectory directory,
                           ObjectProvider<EffectiveRoles> effective,
                           Environment environment,
                           @Value("${app.authn.type:oauth2}") String authMode) {
        this.directory = directory;
        this.effective = effective;
        this.environment = environment;
        this.authMode = authMode;
    }

    @GetMapping("/users")
    public UsersResponse users() {
        List<AdminUser> list = directory.all().stream()
                .map(this::userDto)
                .sorted(Comparator.comparing((AdminUser u) -> sortKey(u)))
                .toList();
        return new UsersResponse(authMode, "oauth2".equals(authMode), list);
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminUser add(@RequestBody AddUserRequest request) {
        return userDto(directory.add(request.identifier(), request.role()));
    }

    @PutMapping("/users/{id}")
    public AdminUser update(@PathVariable("id") Long id, @RequestBody UpdateUserRequest request) {
        return userDto(directory.update(id, request.role() != null, request.role(), request.enabled()));
    }

    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable("id") Long id) {
        directory.remove(id);
    }

    @GetMapping("/events")
    public List<AdminEventDto> events() {
        return directory.recentEvents().stream().map(AdminController::eventDto).toList();
    }

    @GetMapping("/system")
    public SystemInfo system() {
        return new SystemInfo(authMode, List.of(environment.getActiveProfiles()), System.getProperty("java.version"),
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    }

    private AdminUser userDto(AppUser u) {
        EffectiveRoles roles = effective.getIfAvailable();
        Set<String> fromConfig = roles == null ? Set.of() : roles.configRoles(u.getUsername(), u.getEmail());
        return new AdminUser(u.getId(), u.getUsername(), u.getEmail(), u.getDisplayName(), u.getRole(), u.isEnabled(),
                List.copyOf(new TreeSet<>(fromConfig)), u.getLastLoginAt() == null, u.getCreatedAt(), u.getLastLoginAt());
    }

    private static AdminEventDto eventDto(AdminEvent e) {
        return new AdminEventDto(e.getId(), e.getOccurredAt(), e.getActor(), e.getAction(), e.getTarget(), e.getDetail());
    }

    private static String sortKey(AdminUser u) {
        String key = u.displayName() != null ? u.displayName() : (u.username() != null ? u.username() : u.email());
        return key == null ? "" : key.toLowerCase(Locale.ROOT);
    }
}

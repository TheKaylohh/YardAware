package com.shipyard.tracker.security;

import com.shipyard.tracker.domain.AdminEvent;
import com.shipyard.tracker.domain.AppUser;
import com.shipyard.tracker.repo.AdminEventRepository;
import com.shipyard.tracker.repo.AppUserRepository;
import com.shipyard.tracker.service.CurrentUserProvider;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The people known to the app and the roles administrators gave them on the Admin page.
 * Roles set here are added to the roles from the configuration (see {@link EffectiveRoles}).
 * Every change by an administrator is written to the append-only admin_events table and to the SECURITY_AUDIT log.
 */
@Service
public class UserDirectory {

    public static final Set<String> ROLES = Set.of(AppRoles.VIEWER, AppRoles.EDITOR, AppRoles.ADMIN);

    private static final Logger audit = LoggerFactory.getLogger("SECURITY_AUDIT");
    private static final Pattern UNSAFE_TEXT = Pattern.compile("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");

    private final AppUserRepository users;
    private final AdminEventRepository events;
    private final CurrentUserProvider current;
    private final AtomicLong changes = new AtomicLong();

    public UserDirectory(AppUserRepository users, AdminEventRepository events, CurrentUserProvider current) {
        this.users = users;
        this.events = events;
        this.current = current;
    }

    /** Goes up whenever people or roles change, so cached role lookups know to look again. */
    public long changeCount() {
        return changes.get();
    }

    // ------------------------------------------------------------ sign-in

    /**
     * Called at every OpenID Connect sign-in. Creates the person's record the first time (with no role), links a record
     * an administrator prepared by e-mail address, and notes the last sign-in. {@code verifiedEmail} must be null unless
     * the identity provider vouches for the address.
     */
    @Transactional
    public void recordLogin(String username, String verifiedEmail, String displayName) {
        String u = norm(username);
        String e = norm(verifiedEmail);
        if (u == null) {
            return;
        }
        Instant now = Instant.now();
        AppUser user = users.findByUsername(u).orElse(null);

        if (user == null && e != null) {
            AppUser prepared = users.findByEmailAndUsernameIsNull(e).orElse(null);
            if (prepared != null) {
                prepared.setUsername(u);
                user = prepared;
            }
        } else if (user != null && e != null && !e.equals(user.getEmail())) {
            AppUser prepared = users.findByEmailAndUsernameIsNull(e).orElse(null);
            if (prepared != null && !prepared.getId().equals(user.getId())) {
                // Both a record for this sign-in id and one prepared by e-mail exist: keep one, with the role granted.
                if (user.getRole() == null) {
                    user.setRole(prepared.getRole());
                }
                users.delete(prepared);
                users.flush();
                user.setEmail(e);
            } else if (prepared == null && users.findByEmail(e).isEmpty()) {
                user.setEmail(e);
            }
        }

        if (user == null) {
            boolean emailFree = e != null && users.findByEmail(e).isEmpty();
            user = new AppUser(u, emailFree ? e : null, null, null, now);
        } else if (user.getEmail() == null && e != null && users.findByEmail(e).isEmpty()) {
            user.setEmail(e);
        }
        user.setDisplayName(clip(displayName, 255));
        user.setLastLoginAt(now);
        users.save(user);
        changes.incrementAndGet();
    }

    /** The record for this person: by sign-in id, else by an e-mail address added in advance. */
    @Transactional(readOnly = true)
    public Optional<AppUser> find(String username, String email) {
        String u = norm(username);
        String e = norm(email);
        if (u != null) {
            Optional<AppUser> byName = users.findByUsername(u);
            if (byName.isPresent()) {
                return byName;
            }
        }
        return e == null ? Optional.empty() : users.findByEmailAndUsernameIsNull(e);
    }

    // -------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    public List<AppUser> all() {
        return users.findAll();
    }

    @Transactional(readOnly = true)
    public List<AdminEvent> recentEvents() {
        return events.findTop100ByOrderByOccurredAtDescIdDesc();
    }

    /** Adds a person before they sign in. The identifier is an e-mail address or the identity provider's user id. */
    @Transactional
    public AppUser add(String identifier, String role) {
        String id = norm(identifier);
        if (id == null || id.length() > 255 || UNSAFE_TEXT.matcher(id).find()) {
            throw bad("Enter an e-mail address or a user id (up to 255 characters).");
        }
        String normalizedRole = normalizeRole(role);
        boolean isEmail = id.contains("@");
        if (isEmail && !EMAIL.matcher(id).matches()) {
            throw bad("That doesn't look like an e-mail address.");
        }
        String username = isEmail ? null : id;
        String email = isEmail ? id : null;
        boolean exists = isEmail ? users.findByEmail(id).isPresent() : users.findByUsername(id).isPresent();
        if (exists) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, id + " is already in the list.");
        }
        AppUser saved = users.save(new AppUser(username, email, null, normalizedRole, Instant.now()));
        record("USER_ADDED", id, "Role " + (normalizedRole == null ? "none" : normalizedRole));
        return saved;
    }

    /** Changes a person's role and/or whether they may use the app at all. {@code null} leaves a value unchanged. */
    @Transactional
    public AppUser update(Long id, boolean changeRole, String role, Boolean enabled) {
        AppUser user = require(id);
        assertNotSelf(user);
        String label = label(user);
        if (changeRole) {
            String next = normalizeRole(role);
            if (!Objects.equals(next, user.getRole())) {
                record("ROLE_CHANGED", label, (user.getRole() == null ? "none" : user.getRole()) + " -> "
                        + (next == null ? "none" : next));
                user.setRole(next);
            }
        }
        if (enabled != null && enabled != user.isEnabled()) {
            user.setEnabled(enabled);
            record(enabled ? "USER_ENABLED" : "USER_DISABLED", label, null);
        }
        AppUser saved = users.save(user);
        changes.incrementAndGet();
        return saved;
    }

    /** Forgets a person. If they sign in again they come back with no role. */
    @Transactional
    public void remove(Long id) {
        AppUser user = require(id);
        assertNotSelf(user);
        users.delete(user);
        record("USER_REMOVED", label(user), null);
    }

    // ------------------------------------------------------------ helpers

    private AppUser require(Long id) {
        return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    /** Administrators can't change their own access, so the last administrator can't lock everyone out by accident. */
    private void assertNotSelf(AppUser target) {
        AuthUser me = AuthUser.from(SecurityContextHolder.getContext().getAuthentication());
        if (me == null) {
            return;
        }
        String mine = norm(me.username());
        String myEmail = norm(me.email());
        if ((mine != null && mine.equals(target.getUsername())) || (myEmail != null && myEmail.equals(target.getEmail()))) {
            throw bad("You can't change your own access. Ask another administrator.");
        }
    }

    private void record(String action, String target, String detail) {
        String actor = current.currentUser();
        events.save(new AdminEvent(Instant.now(), actor == null ? "unknown" : actor, action, target, detail));
        audit.info("ADMIN_ACTION actor={} action={} target={} detail={}", SecurityAuditListener.safe(actor), action,
                SecurityAuditListener.safe(target), SecurityAuditListener.safe(detail));
        changes.incrementAndGet();
    }

    public static String label(AppUser user) {
        return user.getUsername() != null ? user.getUsername() : user.getEmail();
    }

    /** VIEWER / EDITOR / ADMIN, or null for "no role" (blank or NONE). */
    static String normalizeRole(String role) {
        if (role == null || role.isBlank() || role.trim().equalsIgnoreCase("NONE")) {
            return null;
        }
        String r = role.trim().toUpperCase(Locale.ROOT);
        if (!ROLES.contains(r)) {
            throw bad("The role must be VIEWER, EDITOR, ADMIN or none.");
        }
        return r;
    }

    static String norm(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String v = UNSAFE_TEXT.matcher(value).replaceAll("").trim();
        if (v.isEmpty()) {
            return null;
        }
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

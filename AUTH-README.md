# Auth drop-in for the Yard Tracker POC

Adds sign-in and roles on top of the existing POC. Same app, same data, same screens.

## Install (3 steps)

1. **Unzip over the project root** (the folder with `build.gradle`). It adds the `security` package, two controllers,
   `auth-defaults.properties`, `auth.css` and the auth tests, and replaces `index.html`, `js/api.js`, `js/main.js`
   and `ApiSmokeTest.java`.
2. **Add two dependencies** to `build.gradle`:
   ```
   implementation 'org.springframework.boot:spring-boot-starter-security'
   implementation 'org.springframework.boot:spring-boot-starter-oauth2-client'
   ```
   then reload Gradle.
3. **Run it** and open http://localhost:8080. You land on a login page.

No existing Java file needs editing. The new `AuthCurrentUserProvider` takes over from the stub automatically.

## Test logins (basic mode, dev profile only)

These exist only in `application-dev.properties`, which `./gradlew bootRun` and `./gradlew test` switch on.
Outside the dev profile the app refuses to start in basic or none mode (see `ProductionSafetyGuard`).

| Username | Password | Can do |
|---|---|---|
| viewer | viewer123 | Look at everything (VIEWER role). No Edit toggle. |
| editor | editor123 | Everything above, plus Edit mode (move, add, assemble, edit). |
| admin | admin123 | Same as editor, plus the H2 console. |

The Activity tab now shows who did it: the person's name plus their sign-in id, e.g. `Eddie Editor (editor)`.
Change users in `application-dev.properties`.

## Modes (`app.authn.type` in application.properties)

- `oauth2` (default, the only mode allowed in production): sign in through your identity provider.
- `basic` (dev only): form login with the users above.
- `none` (dev only): authentication off, exactly like the original POC.

### OAuth2 / OIDC

```
app.authn.type=oauth2
spring.security.oauth2.client.registration.idp.client-id=YOUR_CLIENT_ID
spring.security.oauth2.client.registration.idp.client-secret=YOUR_SECRET
spring.security.oauth2.client.registration.idp.scope=openid,profile,email
spring.security.oauth2.client.provider.idp.issuer-uri=https://your-idp/realms/yours

# Who may do what (values of the username claim, or VERIFIED e-mail addresses; case-insensitive).
# Users with none of these roles are refused (app.authz.require-viewer-role=true).
app.authz.oauth2.memory.roles.viewer=dave,erin@corp.com
app.authz.oauth2.memory.roles.editor=alice,bob@corp.com
app.authz.oauth2.memory.roles.admin=carol

# Which token claim is the username. Use one the user cannot change: sub, or oid for Microsoft Entra ID.
app.authz.oauth2.username-claim=sub
# E-mail only grants roles when the token says email_verified=true. Only set this if your IdP guarantees it.
# app.authz.oauth2.trust-unverified-email=false
```

Register this redirect URI at the identity provider: `http://localhost:8080/login/oauth2/code/idp`
(the last part is the registration name you chose, here `idp`).

To use the eLDAP role lookup from the template, copy `ELdapRoleLookupService`, change it to implement
`com.shipyard.tracker.security.RolesLookupService`, and declare it as a bean with `app.authz.oauth2.type=eldap`.
Roles are read at sign-in, so a role change takes effect at the next login.

## What is protected

- Everything on the site needs a signed-in user with the VIEWER, EDITOR or ADMIN role.
- `GET /api/**` needs VIEWER, EDITOR or ADMIN. Every other `/api/**` call needs the EDITOR or ADMIN role.
  `POST /api/hulls` (create a ship) needs ADMIN. This is enforced on the server; hiding the Edit toggle is only a convenience.
- `/actuator/health` is public (status only) for load-balancer probes.
- The API answers 401 (not a redirect), and the frontend sends the browser to sign in.
- Writes carry a CSRF token (cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`).
  Requests with an `Authorization: Basic` header (curl, tests; basic mode only) skip it.
- `/h2-console` exists only in the dev profile, and is admin only there.
- Sign-ins, failed sign-ins, lockouts and refused requests are written to the `SECURITY_AUDIT` logger.
- Basic mode locks a username after 5 failed attempts (and an address after 50) for 15 minutes.
- `GET /api/me` tells the frontend who is signed in.

## Apache dev setup

If Apache serves the static files and proxies only the API, add the sign-in paths too, or sign-in will fail:

```apache
ProxyPass        /api        http://localhost:8080/api
ProxyPass        /auth       http://localhost:8080/auth
ProxyPass        /login      http://localhost:8080/login
ProxyPass        /logout     http://localhost:8080/logout
ProxyPass        /oauth2     http://localhost:8080/oauth2
# plus a matching ProxyPassReverse line for each
```

`server.forward-headers-strategy=framework` is on by default, so the app trusts `X-Forwarded-*` headers.
Make sure Apache sets them (and strips any the client sent), and that port 8080 is not reachable except through Apache.

## Tests

`./gradlew test`. New: `SecurityIntegrationTest` (401, viewer vs editor vs admin, `/api/me`),
`AuthEntryControllerTest` (redirect safety). `ApiSmokeTest` now signs in as the editor.

## Known limits

- Demo passwords are for local testing only; the app won't start with them outside the dev profile.
- Sign out ends the app session only; with single sign-on you may be signed straight back in.
- Sessions live in memory, so a restart signs everyone out.
- Built for Spring Boot 3.5 (the POC's version). Moving to Boot 4 / Spring Security 7: replace
  `AntPathRequestMatcher.antMatcher(...)` in `SecuritySupport` with `PathPatternRequestMatcher.pathPattern(...)`,
  as your template does.

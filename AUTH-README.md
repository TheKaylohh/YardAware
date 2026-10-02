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

## Test logins (basic mode, the default)

| Username | Password | Can do |
|---|---|---|
| viewer | viewer123 | Look at everything. No Edit toggle. |
| editor | editor123 | Everything above, plus Edit mode (move, add, assemble, edit). |
| admin | admin123 | Same as editor, plus the H2 console. |

The Activity tab now shows the real username instead of `demo.user`.
Change users in `auth-defaults.properties`, or define the full `app.authn.basic.users[...]` list in `application.properties`.

## Modes (`app.authn.type` in application.properties)

- `basic` (default): form login with the users above.
- `none`: authentication off, exactly like the original POC.
- `oauth2`: sign in through your identity provider.

### OAuth2 / OIDC

```
app.authn.type=oauth2
spring.security.oauth2.client.registration.idp.client-id=YOUR_CLIENT_ID
spring.security.oauth2.client.registration.idp.client-secret=YOUR_SECRET
spring.security.oauth2.client.registration.idp.scope=openid,profile,email
spring.security.oauth2.client.provider.idp.issuer-uri=https://your-idp/realms/yours

# Who gets write access (usernames or e-mail addresses, case-insensitive). Everyone else can view.
app.authz.oauth2.memory.roles.editor=alice,bob@corp.com
app.authz.oauth2.memory.roles.admin=carol

# Which token claim is the username (default preferred_username, falls back to sub)
app.authz.oauth2.username-claim=preferred_username
```

Register this redirect URI at the identity provider: `http://localhost:8080/login/oauth2/code/idp`
(the last part is the registration name you chose, here `idp`).

To use the eLDAP role lookup from the template, copy `ELdapRoleLookupService`, change it to implement
`com.shipyard.tracker.security.RolesLookupService`, and declare it as a bean with `app.authz.oauth2.type=eldap`.
Roles are read at sign-in, so a role change takes effect at the next login.

## What is protected

- Everything on the site needs a signed-in user.
- `GET /api/**` needs any signed-in user. Every other `/api/**` call needs the EDITOR or ADMIN role.
  This is enforced on the server; hiding the Edit toggle is only a convenience.
- The API answers 401 (not a redirect), and the frontend sends the browser to sign in.
- Writes carry a CSRF token (cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`).
  Requests with an `Authorization: Basic` header (curl, tests; basic mode only) skip it.
- `/h2-console` is admin only.
- `GET /api/me` tells the frontend who is signed in.

## Apache dev setup

If Apache serves the static files and proxies only the API, add the sign-in paths too, or sign-in will fail:

```apache
ProxyPass        /api        http://localhost:8080/api
ProxyPass        /auth       http://localhost:8080/auth
ProxyPass        /login      http://localhost:8080/login
ProxyPass        /logout     http://localhost:8080/logout
ProxyPass        /oauth2     http://localhost:8080/oauth2
ProxyPass        /h2-console http://localhost:8080/h2-console
# plus a matching ProxyPassReverse line for each
```

Behind HTTPS termination, also set `server.forward-headers-strategy=framework`.

## Tests

`./gradlew test`. New: `SecurityIntegrationTest` (401, viewer vs editor vs admin, `/api/me`),
`AuthEntryControllerTest` (redirect safety). `ApiSmokeTest` now signs in as the editor.

## Known limits

- Demo passwords are for local testing only. Use `oauth2` for anything shared.
- Sign out ends the app session only; with single sign-on you may be signed straight back in.
- Sessions live in memory, so a restart signs everyone out.
- Built for Spring Boot 3.5 (the POC's version). Moving to Boot 4 / Spring Security 7: replace
  `AntPathRequestMatcher.antMatcher(...)` in `SecuritySupport` with `PathPatternRequestMatcher.pathPattern(...)`,
  as your template does.

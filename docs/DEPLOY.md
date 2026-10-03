# Deploying the Yard Tracker

## 1. What you need

- PostgreSQL 14+ (the CI test uses 16).
- An OpenID Connect identity provider (Keycloak, Entra ID, Okta ...) with a confidential client.
- HTTPS in front of the app (Apache or a load balancer). The session cookie is `Secure`, and the app refuses to start otherwise.
- Exactly **one** app instance for now: sessions live in memory (see "Known limits").

Build: `./gradlew bootJar`, or `docker build -t yard-tracker .`. Run with `SPRING_PROFILES_ACTIVE=prod` (the Dockerfile sets it).

## 2. Database

Create two roles. The **owner** runs the Flyway migrations; the **app** role is what the running application uses.

```sql
create role yard_owner login password '...';
create role yard_app   login password '...';
create database yard owner yard_owner;
\c yard
revoke all on schema public from public;
grant usage on schema public to yard_app;
alter default privileges for role yard_owner in schema public
  grant select, insert, update on tables to yard_app;
alter default privileges for role yard_owner in schema public
  grant usage, select on sequences to yard_app;
```

`activities` should additionally be insert-only for the app (the database triggers already block UPDATE/DELETE for everyone, this is a second lock):
after the first start, run `revoke update on activities from yard_app;` as `yard_owner`.

Set `DB_URL`, `DB_USER`/`DB_PASSWORD` (the app role) and `DB_MIGRATION_USER`/`DB_MIGRATION_PASSWORD` (the owner).

Migrations are `src/main/resources/db/migration`. **Never edit or delete a migration that has been applied anywhere.** Add a new `V4__...sql` instead. (`V3` drops and recreates `items` and `activities`; it was written for a database with no real history.)

## 3. Environment variables

| Variable | Meaning |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://host:5432/yard` and the app role |
| `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD` | the owner role (defaults to the app role) |
| `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET` | from your identity provider |
| `OIDC_USERNAME_CLAIM` | `sub` (default), or `oid` for Microsoft Entra ID. Use a claim users cannot edit |
| `YARD_VIEWERS`, `YARD_EDITORS`, `YARD_ADMINS` | comma-separated values of that claim (or **verified** e-mail addresses) |

Redirect URI to register at the identity provider: `https://<your host>/login/oauth2/code/idp`.

## 4. First start

On an empty database the app creates, by itself: the 11 phases and the whole hierarchy (`HierarchyLoader`) and the 13 yard zones (`ZoneLoader`). There are **no ships**.
An admin creates each ship once (this also generates its planned units, blocks and sections):

```
POST /api/hulls     {"code": "S044", "name": "Hull 44", "color": "#2D6CDF"}      (ADMIN only, needs the CSRF header)
```

There is no screen for this yet. From a browser console while signed in as an admin:

```js
const t = document.cookie.match(/XSRF-TOKEN=([^;]*)/)[1];
fetch('/api/hulls', {method:'POST', credentials:'same-origin',
  headers:{'Content-Type':'application/json','X-XSRF-TOKEN':decodeURIComponent(t)},
  body: JSON.stringify({code:'S044', name:'Hull 44', color:'#2D6CDF'})}).then(r => r.json()).then(console.log)
```

## 5. Health, logs, backups

- Probes: `/actuator/health/liveness` and `/actuator/health/readiness` (public, no details). `/actuator/health` includes the database.
- Logs go to stdout. Security events (sign-ins, lockouts, refused requests) are on the `SECURITY_AUDIT` logger: ship stdout to your log system and keep that logger.
  The history of who moved what is also in the `activities` table.
- Back up PostgreSQL (`pg_dump -Fc`, or your platform's snapshots) at least daily, keep copies off the server, and **do a test restore** into a scratch database before go-live and then every quarter.
  Retention should match your records policy.

## 6. Go-live checklist (what the automated tests cannot do)

1. Deploy to a staging host with HTTPS and the real identity provider.
2. Sign in as an admin, an editor and a viewer: each sees what their role allows. Sign in as someone in none of the lists: refused.
3. Check what the Activity tab shows as the person for a move: it records `Name (id)`.
4. Create a ship with `POST /api/hulls`, place an item, move it, assemble two pieces.
5. Restart the app: everyone is signed out, the data is still there.
6. Restore last night's backup into a scratch database and open the app against it.

## 7. Known limits

- Sessions are in memory: one instance only, and a restart signs everyone out. Spring Session JDBC is the next step if you need more than one.
- Roles come from environment variables, so changing them needs a restart. Plan the move to identity-provider groups.
- `/api/items` returns every active item on each load; fine for a few ships, add paging before dozens.
- Spring Boot 3.5 left open-source support on 2026-06-30. Move to Boot 4.x soon (README of auth: "Known limits" lists the one known code change).

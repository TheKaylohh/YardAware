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

`activities` and `admin_events` should additionally be insert-only for the app (the database triggers already block UPDATE/DELETE for everyone, this is a second lock),
and the app needs DELETE on `app_users` for the *Remove* button on the Admin page (the default grants above leave DELETE out).
After the first start (the migrations create the tables), run as `yard_owner`:

```sql
revoke update on activities   from yard_app;
revoke update on admin_events from yard_app;
grant  delete on app_users    to   yard_app;
```

Set `DB_URL`, `DB_USER`/`DB_PASSWORD` (the app role) and `DB_MIGRATION_USER`/`DB_MIGRATION_PASSWORD` (the owner).

Migrations are `src/main/resources/db/migration`. **Never edit or delete a migration that has been applied anywhere.** Add a new `V5__...sql` instead. (`V4` adds `app_users` and the append-only `admin_events`.) (`V3` drops and recreates `items` and `activities`; it was written for a database with no real history.)

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
An admin creates each ship once (this also generates its planned units, blocks and sections): sign in as an administrator, open **Admin → Ships** and use *Add ship*
(code such as `S044`, a name and a colour). The same call is available as `POST /api/hulls {"code": "S044", "name": "Hull 44", "color": "#2D6CDF"}` (ADMIN only, needs the CSRF header).

**Bootstrapping access.** Nobody can use the app until they have a role. Put the first administrator(s) in `YARD_ADMINS` (their username claim or verified e-mail address). They sign in and open
**Admin → People and access** to add everybody else, by e-mail address in advance, or by changing the role of people who have signed in and are waiting with no access.
Keep at least two people in `YARD_ADMINS`: they are the break-glass accounts that cannot be disabled or locked out from the Admin page, and nobody may change their own access.

## 5. Health, logs, backups

- Probes: `/actuator/health/liveness` and `/actuator/health/readiness` (public, no details). `/actuator/health` includes the database.
- Logs go to stdout. Security events (sign-ins, lockouts, refused requests) are on the `SECURITY_AUDIT` logger: ship stdout to your log system and keep that logger.
  The history of who moved what is also in the `activities` table.
- The Admin page's data (`app_users`, `admin_events`) is in the same database, so the same backup covers it.
- Back up PostgreSQL (`pg_dump -Fc`, or your platform's snapshots) at least daily, keep copies off the server, and **do a test restore** into a scratch database before go-live and then every quarter.
  Retention should match your records policy.

## 6. Go-live checklist (what the automated tests cannot do)

1. Deploy to a staging host with HTTPS and the real identity provider.
2. Sign in as an admin, an editor and a viewer: each sees what their role allows. Sign in as someone in none of the lists: refused.
3. Check what the Activity tab shows as the person for a move: it records `Name (id)`.
4. Add a ship on the Admin page, place an item, move it, assemble two pieces.
4a. On the Data page change a zone and a phase for two items and save; export to Excel, change a zone in the sheet, import it, read the preview, apply it. Check the Activity tab names you and the file.
4b. On the Admin page add a person by e-mail, have them sign in, change their role, and confirm the change works on their next click. Disable them and confirm they are refused. Confirm your own row is locked.
5. Restart the app: everyone is signed out, the data is still there.
6. Restore last night's backup into a scratch database and open the app against it.

## 7. Known limits

- Sessions are in memory: one instance only, and a restart signs everyone out. Spring Session JDBC is the next step if you need more than one.
- Roles: `YARD_*` variables are the always-on baseline (changing them needs a restart); everything else is managed on the Admin page and stored in `app_users`. Plan the move to identity-provider groups if you want roles to follow your directory.
- `/api/items` returns every active item on each load; fine for a few ships, add paging before dozens.
- Spring Boot 3.5 left open-source support on 2026-06-30. Move to Boot 4.x soon (README of auth: "Known limits" lists the one known code change).

# Yard Tracker

A proof of concept for tracking where things are in a shipyard: grand blocks, blocks, sections, sub-assemblies,
units, pipe and outfitting batches, and large tools. Every item has a location on a yard map and a full history.

Java 21, Spring Boot 3.5, Gradle, H2 (file database), and a plain JavaScript frontend served by the same app.

## Run it

You need Java 21. Gradle is not required; the wrapper downloads it on first use.

```
./gradlew bootRun        # Windows: gradlew.bat bootRun   (uses the dev profile automatically)
```

Production: see `docs/DEPLOY.md` (PostgreSQL, identity provider, first start, backups, go-live checklist). A `Dockerfile` is included.
The app refuses to start outside the dev profile if a development-only setting is still on, and refuses dev mode together with the prod profile.

Open http://localhost:8080 and sign in (see below). You land on the Home page; the menu bar at the top leads to the other pages.

**Test logins:** `viewer` / `viewer123` (read only), `editor` / `editor123` (can edit), `admin` / `admin123` (can edit, plus H2 console).
Details, OAuth2 setup and the Apache proxy lines are in `AUTH-README.md`.

The first start creates `./data/yard` and fills it with demo data (3 hulls, 13 zones, about 24 items).
To start over with fresh demo data, stop the app and delete the `data` folder.
To start with an empty database, set `shipyard.seed-demo-data=false` in `application.properties`
(the zones and the plan load anyway; an admin then adds ships on the Admin page, see `docs/DEPLOY.md`).

Run the tests with `./gradlew test`. Most use an in-memory database and never touch `./data`. `ProductionPathIntegrationTest` starts a real PostgreSQL in Docker
(Flyway, schema validation, OAuth2 wiring, database-managed roles, append-only audit trails) and is skipped when Docker is not available.

If the wrapper fails to start, install Gradle once and run `gradle wrapper`, or open the folder in IntelliJ IDEA,
which imports the Gradle build directly.

The H2 console is at http://localhost:8080/h2-console (JDBC URL `jdbc:h2:file:./data/yard`, user `sa`, no password).
It is admin-only (sign in as `admin`) and only exists in the dev profile.

## The pages

A menu bar on every page shows only the pages the signed-in person may use. The server enforces the same rules.

| Page | Who | What it is for |
|---|---|---|
| **Home** (`/`) | everyone | Welcome with the person's name and role, how the app works, shortcuts, the yard at a glance (items per ship and status, items per production phase, busiest zones) and the latest activity. |
| **Map** (`/map.html`) | everyone; Edit mode for editors | The yard map described below. `/map.html?q=S041-BA-U01` opens it with that item searched and focused. |
| **Data** (`/data.html`) | everyone reads; editors change | Every item in a spreadsheet-style grid. Sort, filter, search, copy. Editors change **Zone**, **Phase** and **Specs** (yellow headers) by typing, pasting blocks from Excel, filling down or undoing, then press **Save** once. **Check** shows what would happen without saving. |
| **Import** (`/import.html`) | editors | Upload an `.xlsx`, see every change and every problem, then apply. Nothing is saved before you press Apply. |
| **Admin** (`/admin.html`) | admins | Who may view, edit or administer; add ships; the log of what administrators did; basic system facts. |

**Data page rules.** The grid uses the same rules as the map: an item that is still planned and gets a zone is placed on the yard, an item on the yard is moved,
a phase must be on the item's route and only items on the yard can change phase, specs must be a JSON object (`{}` clears them). A blank cell means "leave as it is".
All edits are saved in one transaction with a history entry per change, naming the person and "Bulk edit in the data grid". If anyone changed an item after you loaded the page,
that row is refused instead of overwriting their work. Keyboard: arrows, Tab, Enter or F2 to edit, Delete to undo a cell, Ctrl+C / Ctrl+V, Ctrl+D fill down, Ctrl+Z / Ctrl+Y.

**Excel format.** *Export Excel* on the Data page (or `GET /api/export/items.xlsx`) writes a workbook with the sheets *Items*, *Zones*, *Phases* and *Read me*.
Change the yellow columns (Zone, Phase, Specs) and upload it on the Import page. Grey columns are for reading and are ignored, as are columns you add.
Leave the *Version* column alone: it is how the app notices that someone else changed an item after the sheet was made. Columns may be reordered; the header row may be a few rows down.
The importer reads `.xlsx` only (not `.xls` or `.csv`), up to 2 MB and 5000 rows, and never creates items. Rows with unknown items, zones, phases or malformed specs are listed as problems.
Either fix them, or tick *Skip the rows with problems* to apply the rest. History entries say "Imported from <file name>".

**People and roles.** Roles are stored in the database and edited on the Admin page. People appear there the first time they sign in (with no access until an admin grants some),
or an admin adds them in advance by e-mail address or user id. Roles set in the configuration (`YARD_ADMINS` and friends) are always kept, and administrators named there cannot be locked out from the page
(disabling takes away every other person's access, configured or not). Changes apply on the person's next request without signing in again. Administrators cannot change their own access.
Every change is written to an append-only log (`admin_events`). Disabling someone keeps their record and gives them no access.
Database roles apply when people sign in through the identity provider (OAuth2). In the dev `basic` mode, roles still come from `application-dev.properties`.

## The map

**View mode.** Hover an item to preview it in the right-hand panel. Click it to pin the panel, then use the tabs:
Details, Children (the component tree, including pieces that were assembled into it), Activity (every move and edit),
and Hull. Drag the map to pan, scroll to zoom. Hull chips in the top bar show or hide ships; the search box dims everything
that doesn't match (press Enter to jump to the first match).

**Edit mode** (toggle on the Map page, yellow underline while on).
- Drag a tile onto another zone to move it. Or use "Move to another zone" in the Details tab to add a note.
- Add item creates something new. Grand blocks can leave the name blank and get one built from hull and tag (S041-EA500).
- Assemble: click Assemble, pick two or more items, click Continue, name the result. The pieces leave the map and are
  kept under the new item's Children tab with their history intact. Pieces must belong to the same hull, and tools can't be assembled.
- Edit name, hull, tag, quantity and specs in the Details tab.

**Map symbols.** Outline colour is the hull (S041 blue, S042 orange, S043 purple). Tools have no hull and get a dashed grey outline.
The two letters are the item type (SA sub-assembly, SC section, BK block, GB grand block, UN unit, PO pipe/outfitting, TL tool).
A dark pill on a tile is the batch quantity.

**Grand block tags.** Area code plus three digits, where the first digit is the level. `EA500` is engine room, level 5.

| Code | Area | Code | Area |
|---|---|---|---|
| AA | Aft area | FA | Forward area |
| BA | Double hull | HA | Crew compartments |
| DA | Deck area | LA | LNG storage |
| EA | Engine room | PA | Passage |

## Layout

```
src/main/java/com/shipyard/tracker
  domain/    entities and enums (Item, Hull, Zone, Activity, AppUser, AdminEvent ...)
  repo/      Spring Data repositories
  service/   ItemService (all business rules), BulkItemService (data grid + Excel import), ItemSheets (.xlsx),
             GridService, SummaryService (Home), DTOs, CurrentUserProvider
  security/  sign-in modes, UserDirectory + EffectiveRoles (roles in the database), RoleRefreshFilter
  web/       ApiController, GridController, SummaryController, AdminController, MeController
  config/    loaders and DemoDataSeeder
src/main/resources/static
  index.html (Home), map.html, data.html, import.html, admin.html
  css/       app.css (map), nav.css (menu bar), pages.css (Home, Data, Import, Admin)
  js/        nav.js (menu bar), home.js, grid.js, import.js, admin.js, report.js, api.js, util.js
             map.js, main.js, panel.js, forms.js, state.js (the map)
  img/yard.svg   placeholder site schematic
src/main/resources/db/migration   V1..V4 (PostgreSQL); the dev profile lets Hibernate create the H2 tables
tools/make_map.py   regenerates yard.svg
```

## API

| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | /api/me | signed in | who is signed in, roles, `canEdit` |
| GET | /api/meta, /api/hulls, /api/zones, /api/phases | viewer | reference data |
| GET | /api/items, /api/items/{id}, /{id}/tree, /{id}/activity | viewer | the map data |
| POST | /api/items/{id}/place, /move, /phase; POST /api/items/assemble; PUT /api/items/{id} | editor | the map's edit actions |
| GET | /api/summary | viewer | numbers for the Home page |
| GET | /api/grid/items | viewer | every item, as the Data grid shows it |
| POST | /api/grid/items/check, /api/grid/items/save | editor | dry run / save of `{columns, rows, skipErrors, source}` (rows: `{row, name, version, zone, phase, specs}`) |
| GET | /api/export/items.xlsx | viewer | the Excel sheet |
| POST | /api/import/items?apply=false\|true&skipErrors=false\|true | editor | multipart `file`; preview or apply an uploaded `.xlsx` |
| POST | /api/hulls | admin | create a ship and its planned items `{code, name, color}` |
| GET, POST | /api/admin/users | admin | list people; add one `{identifier, role}` |
| PUT, DELETE | /api/admin/users/{id} | admin | change `{role: VIEWER\|EDITOR\|ADMIN\|NONE, enabled}`; remove |
| GET | /api/admin/events, /api/admin/system | admin | what admins did; basic system facts |
| GET | /actuator/health | public | health probe (status only) |

Errors are returned as JSON with a readable `message`.

## Decisions worth knowing about

- **Assembled pieces are archived, not deleted.** They get status `CONSUMED`, point at their parent, and disappear from the map.
  That keeps the Children tree and each piece's history. Permanently deleting them is not implemented.
- **Locations are zone-level.** An item is "in the Assembly Hall", not at coordinates. Tiles are laid out in a grid inside
  the zone, so their position within a zone is not meaningful.
- **The map is a hand-rolled SVG** with pan and zoom instead of a mapping library, so there are no dependencies and it works offline.
- **Zone outlines live in the database** as polygon points in the 1600 x 1000 space of `static/img/yard.svg`.
  If you replace the background with a real site plan or aerial image, keep that coordinate space (or rescale the zone points).
  The zone rectangles in `DemoDataSeeder` and `tools/make_map.py` must agree.
- **Who made a change** comes from the signed-in user (`AuthCurrentUserProvider`). With authentication off it falls back to `shipyard.demo-user`.

## Not built yet

Partial moves of a batch (move 20 of 48 pipe spools), splitting an assembly back into pieces, permanently deleting items,
editing zones and hulls in the UI (ships can be added, not edited), creating items from an Excel file (the importer updates existing items only),
a real aerial map, and live position sources (GPS/RFID).

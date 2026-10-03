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

Open http://localhost:8080 and sign in (see below).

**Test logins:** `viewer` / `viewer123` (read only), `editor` / `editor123` (can edit), `admin` / `admin123` (can edit, plus H2 console).
Details, OAuth2 setup and the Apache proxy lines are in `AUTH-README.md`.

The first start creates `./data/yard` and fills it with demo data (3 hulls, 13 zones, about 24 items).
To start over with fresh demo data, stop the app and delete the `data` folder.
To start with an empty database, set `shipyard.seed-demo-data=false` in `application.properties`
(the zones and the plan load anyway; an admin then creates ships with `POST /api/hulls`, see `docs/DEPLOY.md`; there is no screen for that yet).

Run the tests with `./gradlew test`. Most use an in-memory database and never touch `./data`. `ProductionPathIntegrationTest` starts a real PostgreSQL in Docker
(Flyway, schema validation, OAuth2 wiring, append-only audit trail) and is skipped when Docker is not available.

If the wrapper fails to start, install Gradle once and run `gradle wrapper`, or open the folder in IntelliJ IDEA,
which imports the Gradle build directly.

The H2 console is at http://localhost:8080/h2-console (JDBC URL `jdbc:h2:file:./data/yard`, user `sa`, no password).
It is admin-only (sign in as `admin`) and only exists in the dev profile.

## How it works

**View mode.** Hover an item to preview it in the right-hand panel. Click it to pin the panel, then use the tabs:
Details, Children (the component tree, including pieces that were assembled into it), Activity (every move and edit),
and Hull. Drag the map to pan, scroll to zoom. Hull chips in the top bar show or hide ships; the search box dims everything
that doesn't match (press Enter to jump to the first match).

**Edit mode** (toggle in the top bar, yellow underline while on).
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
  domain/    entities and enums (Item, Hull, Zone, Activity, AreaCode ...)
  repo/      Spring Data repositories
  service/   ItemService (all business rules), DTOs, CurrentUserProvider
  web/       ApiController (REST)
  config/    DemoDataSeeder
src/main/resources/static
  index.html, css/app.css
  js/        main.js (wiring), map.js, panel.js, forms.js, api.js, state.js, util.js
  img/yard.svg   placeholder site schematic
tools/make_map.py   regenerates yard.svg
```

## API

| Method | Path | Purpose |
|---|---|---|
| GET | /api/meta, /api/hulls, /api/zones | reference data |
| GET | /api/items | everything currently on the yard |
| GET | /api/items/{id}, /{id}/tree, /{id}/activity | one item, its components, its history |
| POST | /api/items | create |
| PUT | /api/items/{id} | edit name, hull, tag, quantity, specs |
| POST | /api/items/{id}/move | move to a zone `{zoneId, note}` |
| POST | /api/hulls | **admin only**: create a ship and its planned items `{code, name, color}` |
| GET | /actuator/health | health probe (public, status only) |
| POST | /api/items/assemble | `{childIds, name, type, tag, zoneId, specs, note}` |

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
editing zones and hulls in the UI, a real aerial map, and live position sources (GPS/RFID).

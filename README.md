# Better Battle — starter code

Created by **Koidu**. Mod + web platform for Hypixel Build Battle and Speed Builders.
Website: `battle.aspir3.net` · API: `api.aspir3.net/bb/v1`

## Layout

```
api/
  db/migrations/001_init.sql     PostgreSQL 16 + TimescaleDB schema (all server tables)
  src/routes/mod.ts              Mojang handshake, /mod/bootstrap, idempotent batched /mod/sync
  src/routes/public.ts           Profiles, leaderboard, search, rank history, heatmap, achievements
  src/jobs/crawler.ts            Finds and refreshes every Build Battle player (global ranking)
  src/jobs/daily.ts              Rank snapshots, leaderboard rebuild
  src/index.ts                   Server entry: API under /bb/v1 + serves the website
web/                             The website (plain HTML/CSS/JS, no build step)
tools/preview.mjs                Website preview with sample data
mod/
  core/                          Java 8-compatible, no Minecraft imports: shared by both builds
    store/LocalDatabase.java     The mod's own SQLite DB: works with no server at all
    game/GameStateMachine.java   IDLE → LOBBY → BUILDING → VOTING → RESULTS
    game/MatchRecord.java, Grades.java
    parse/ChatParser.java        Regex table driven by patterns.json (server can push updates)
    sync/SyncService.java        Outbox → server, backoff, full-resync on request
    sync/ApiClient.java          HTTP interface, implemented per loader
    achieve/AchievementRules.java All-Wood, Mode Marathon, tiered tracks
  core/src/main/resources/betterbattle/patterns.json   PLACEHOLDER regexes
  fabric-26.3/                   (next) thin adapter: mixins for chat, scoreboard, block place, UI
  forge-1.8.9/                   (next) same adapter for Forge 1.8.9; maps old block ids to modern names
```

## What's still a stub

- `patterns.json` regexes are guesses. Record real chat from a few games per mode and replace them.
- The API's TypeScript has not been compiled or run yet. Its SQL was tested against PostgreSQL 16,
  and the website was tested in Chrome against `tools/preview.mjs`. Expect a few small fixes on first `docker compose up`.
- Hypixel field names for perfect builds and Speed Builders wins in `achievementEngine.ts` need checking against a real API response.
- Accounts on the website (sign up, Discord login, pinning, bio) are not built yet. Profiles are public and read-only.
- The two loader adapters and UI screens (toasts, `/bb` menu, local profile, credits screen).

## Build metrics (instead of a vague "accuracy")

| Metric | Formula | Where the data comes from |
|---|---|---|
| Crowd score | your score ÷ (voters × 18). Voters = lobby minus you (and your teammate in Teams); 18 = Legendary | results chat |
| SPB precision | blocks the build needs ÷ (blocks placed + broken) | your own placements, `spb_builds.block_count` |
| Theme range | distinct themes you've built | `user_themes` |

Country ranking is intentionally left out: there's no reliable country source.

## Global rank, levels, achievements

- **Global rank** = Hypixel Build Battle score only, among **every Build Battle player we can discover**, not just mod users.
  `api/src/jobs/crawler.ts` finds players from mod users' lobby rosters, their guilds, Hypixel's Build Battle
  leaderboards and website searches, then refreshes scores from the Hypixel API on a schedule (top 10k every 6 h).
  The ranking is keyed by UUID in Redis (`lb:bb_score`); scores are never trusted from the client.
- **Titles**: `progress/Titles.java` and `api/src/lib/titles.ts` (Rookie 0 → Ascended 500,000, with § colours).
- **Aspiring Level**: XP from n to n+1 = 500 + 100n. Same math in `progress/Level.java` and `api/src/services/level.ts`.
- **Catalog**: `api/db/seeds/achievements.yaml`, with 14 tiered tracks (78 tiers), 26 one-off challenges, and a pool of 10 weekly challenges.

## Local database guarantees

- Every game is committed to `config/betterbattle/<uuid>.db` before any network call.
- Stats are always recomputed from matches, both locally and on the server, so they can't drift.
- Each match has a `nonce`; re-uploading the same match is a no-op on the server.
- If the server loses data, it sets `fullResyncRequested`, and every mod re-uploads its whole history.
- `/bb export` (planned in `store/Exporter`) dumps JSON/CSV. The `.db` file opens in any SQLite viewer.

## Dependencies

- Mod: `org.xerial:sqlite-jdbc` (shade into both jars), Gson (already in Minecraft).
- API: Node 20+, `fastify`, `zod`, `pg`, `ioredis`, `@fastify/rate-limit`, `argon2`.

## Preview the website (no setup)

```
node tools/preview.mjs
```
Open http://localhost:5173. This uses sample data (Koidu's Hypixel numbers are real) and needs nothing installed except Node.

## Running it for real on your laptop

**Windows:** install Docker Desktop, then double-click **`bb.cmd`**. The first time, it asks for your Hypixel API key
and writes `.env` for you, starts everything, and opens http://localhost:4000.
**macOS / Linux:** `./bb.sh`

| Command | What it does |
|---|---|
| `bb start` (or double-click) | Start everything and open the site |
| `bb stop` | Stop (all data is kept) |
| `bb restart` | Restart the app, e.g. after editing `.env` |
| `bb logs` | Watch the app's logs |
| `bb status` | Show what's running |
| `bb seed` / `bb seed "Guild Name"` | Add a guild to the ranking (default: Aspiring) |
| `bb snapshot` | Record today's ranks now (normally automatic at 00:05 UTC) |
| `bb update` | Rebuild after getting new code |
| `bb reset` | Delete all data and start fresh (asks first) |

Run commands from a terminal in the project folder (`.\bb seed` in PowerShell, `bb seed` in Command Prompt).
To go public, point a Cloudflare Tunnel at `localhost:4000` for `battle.aspir3.net`.

## Design docs
## Design docs

- Architecture plan: https://claude.ai/artifact/NCbTPpibjoCtzUcMMAh9gp
- Profile page mockup: https://claude.ai/artifact/GaD1XVZ2wTdLs18ofSRcSE

(Both links are private until you share them.)

# Better Battle — starter code

Created by **Koidu**. Mod + web platform for Hypixel Build Battle and Speed Builders.
Website: `battle.aspir3.net` · API: `api.aspir3.net/bb/v1`

## Layout

```
api/
  db/migrations/001_init.sql     PostgreSQL 16 + TimescaleDB schema (all server tables)
  src/routes/mod.ts              Mojang handshake, /mod/bootstrap, idempotent batched /mod/sync
  src/routes/public.ts           Profiles, leaderboard, search, rank history, heatmap, achievements
  src/jobs/crawler.ts            Discovers players on demand for the global ranking
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

## What's not finished

- `patterns.json` matches real Solo and Speed Builders games (Nov 2026). Teams, Pro and Guess the Build still need a
  `/bb debug` log each to confirm their wording.
- The mod has compiled for both versions but needs real play-testing.
- Hypixel field names for perfect builds and Speed Builders wins in `achievementEngine.ts` need checking against a real API response.
- Speed Builders tracking is checked against a real game (Nov 2026): rounds, builds and the clock come from the
  sidebar, perfect builds and accuracy from chat. `mod/core/src/test/Replay.java` replays a `debug.log` through it.
  Ghost replays (showing where the record holder placed blocks) are left out on purpose: that kind of build help
  could break Hypixel's allowed-mods rules.
- Block breaks come from left clicks on a block. Check on 1.8.9 that Forge fires `LEFT_CLICK_BLOCK` on the client in
  Speed Builders; if not, Measure Twice and precision will be too generous there.
- Every one-off challenge has a detector. The Guess the Build chat pattern is a guess to check with `/bb debug`.

To claim a profile on the website: sign up, open Account, get a code, and run `/bb link <code>` in game.

## Plans, payments and the Discord bot

Three plans: Builder (free), Architect and Master Builder. Nothing inside a game is paid; plans add profile style and
deeper stats on the website and in Discord. `api/src/lib/plans.ts` decides every gate. Payments go through Stripe
Checkout; setup is in [`docs/plans-setup.md`](docs/plans-setup.md), and until it is done the Plans page says "Coming soon".

Bot commands: `/stats`, `/leaderboards`, `/top`, `/compare`, `/records`, `/weekly`, `/recent`, `/plans`, `/help`,
`/verify`, `/download`, `/feed`, plus the moderation and ticket commands.

## Build metrics (instead of a vague "accuracy")

| Metric | Formula | Where the data comes from |
|---|---|---|
| Crowd score | your score ÷ (voters × 18). Voters = lobby minus you (and your teammate in Teams); 18 = Legendary | results chat |
| SPB precision | blocks the build needs ÷ (blocks placed + broken) | your own placements, `spb_builds.block_count` |
| Theme range | distinct themes you've built | `user_themes` |

Country ranking is intentionally left out: there's no reliable country source.

## Global rank, levels, achievements

- **Global rank** = Hypixel Build Battle score only, among **every Build Battle player we can discover**, not just mod users.
  `api/src/jobs/crawler.ts` discovers players from hourly Hypixel Build Battle leaderboards, mod users' lobby rosters,
  website searches, mod logins and one-off imports. It does not re-check players on a timer: sightings can queue a
  player again after 7 days, while player-driven lookups refresh cached data after 1 hour.
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
| `bb seed` / `bb seed "Guild Name"` | Add a guild to the ranking (default: Aspiring); each member is looked up once |
| `bb snapshot` | Record today's ranks now (normally automatic at 00:05 UTC) |
| `bb update` | Pull the latest code with fast-forward only, then rebuild; a failed pull stops the rebuild |
| `bb reset` | Delete all data and start fresh (asks first) |

Run commands from a terminal in the project folder (`.\bb seed` in PowerShell, `bb seed` in Command Prompt).
To go public, point a Cloudflare Tunnel at `localhost:4000` for `battle.aspir3.net`.

## Database backups

- `bb backup` writes a compressed database dump to `backups/` and keeps the newest 14.
- `bb restore <file>` replaces the current database after you type `RESTORE`.
- `bb backup-daily` schedules a daily 04:00 backup on Windows, or prints a crontab line on macOS/Linux. Use `bb backup-daily off` to remove the schedule.
- Set `BACKUP_COPY_DIR` in `.env` to copy each successful backup, including restore safety backups, to another folder; that folder also keeps the newest 14 Better Battle dumps.

Backups contain emails, password hashes, and Discord IDs. Keep them private and off GitHub. For protection if the PC is lost, set `BACKUP_COPY_DIR` to a separate location such as OneDrive.

## Discord bot

The bot starts inside the API when `DISCORD_BOT_TOKEN` is set. Never share the bot token.

1. In the Discord Developer Portal, open the existing app used for website login, go to **Bot**, choose **Reset Token**, and copy the token into `DISCORD_BOT_TOKEN` in `.env`.
2. In Discord, open **User Settings → Advanced** and enable **Developer Mode**. Right-click the server, choose **Copy Server ID**, and paste it into `DISCORD_GUILD_ID` in `.env`.
3. Run `bb update`, then `bb logs`. Open the invite link printed by the API.
4. In the server, run `/setup`, then run `/ticket-panel` in the support channel.

Commands: `/setup staff-role log-channel`, `/download`, `/verify name`, `/unverify`, `/stats [player]`, `/ticket-panel`,
`/warn user reason`, `/warnings user`, `/clearwarn id`, `/timeout user duration reason`, `/untimeout user`,
`/kick user reason`, `/ban user reason delete_messages`, `/unban user_id reason`, and `/purge amount [user]`.

## Testing the mod

Latest jars are served from [the install page](https://battle.aspir3.net/install) and include SHA-256 checksums.

- **26.3:** Fabric Loader 0.20.1+ and Fabric API for 26.3, then drop `betterbattle-fabric-26.3-*.jar` into `mods/`.
- **1.8.9:** Forge 1.8.9 (11.15.1.2318), then drop `betterbattle-forge-1.8.9-*.jar` into `mods/`.

In game: press **B** or type `/bb`. Other commands: `/bb achievements`, `/bb recap` (last game), `/bb weekly`,
`/bb news` (What's New), `/bb settings`, `/bb sync`, `/bb palette [3-12]`, `/bb debug`, `/bb credits`.

The first B press after an update opens What's New. Patch notes live in `web/updates.json`; run
`python3 tools/gen-catalog.py` to copy them into the mod.

Every feature can be switched off in game under **Settings** (B menu), or in `config.json`:

| Setting | Default | What it does |
|---|---|---|
| `spbTimer` | `true` | Speed Builders stopwatch with your best time |
| `spbShowRecord` | `true` | Also show the Better Battle world record on it |
| `recap` | `true` | Short summary in chat after each game |
| `whatsNew` | `true` | Open What's New once after the mod updates |
| `weeklyAlerts` | `true` | Chat message when you finish a weekly challenge |
| `updateNotices` | `true` | Chat message when a newer version is out (it never downloads anything) |

The mod asks `GET /mod/latest?loader=fabric|forge&mc=<version>` for `{ "version", "sha256"?, "notes"? }` when you join
and every 6 hours. A 404 means no notice. The link it shows is always `<website>/install`, never one from the response.

Settings live in `config/betterbattle/config.json`:

| Setting | Default | What it does |
|---|---|---|
| `apiBase` | `https://battle.aspir3.net/bb/v1` | Stats server. Use `http://localhost:4000/bb/v1` to test against the server on the same PC |
| `website` | `https://battle.aspir3.net` | Where "View Profile" goes |
| `anyServer` | `false` | Track games on any server, not only Hypixel |
| `debug` | `false` | Write chat and sidebar lines to `config/betterbattle/debug.log` (toggle with `/bb debug`) |
| `shareVotes` | `true` | Save your own votes for Build Battle Wrapped |
| `toasts`, `chatMessages` | `true` | Achievement toasts and chat lines |

**Tuning game detection:** Hypixel's exact chat wording isn't documented, so `patterns.json` holds best guesses.
Turn on `/bb debug`, play a game or two, and the log shows the real lines to fix the patterns with.

## Admin: votes

Set `ADMIN_TOKEN` in `.env` (a long random string), restart, and open `/admin.html`. It shows how every mod user
votes and who they vote for. Only votes cast by people running the mod are known: Hypixel never reveals other players' votes.

## Design docs
## Testing the mod

Latest jars are served from [the install page](https://battle.aspir3.net/install) and include SHA-256 checksums.

- **26.3:** Fabric Loader 0.20.1+ and Fabric API for 26.3, then drop `betterbattle-fabric-26.3-*.jar` into `mods/`.
- **1.8.9:** Forge 1.8.9 (11.15.1.2318), then drop `betterbattle-forge-1.8.9-*.jar` into `mods/`.

In game: press **B** or type `/bb`. Other commands: `/bb achievements`, `/bb sync`, `/bb palette [3-12]`,
`/bb debug`, `/bb credits`.

Settings live in `config/betterbattle/config.json`:

| Setting | Default | What it does |
|---|---|---|
| `apiBase` | `https://battle.aspir3.net/bb/v1` | Stats server. Use `http://localhost:4000/bb/v1` to test against the server on the same PC |
| `website` | `https://battle.aspir3.net` | Where "View Profile" goes |
| `anyServer` | `false` | Track games on any server, not only Hypixel |
| `debug` | `false` | Write chat and sidebar lines to `config/betterbattle/debug.log` (toggle with `/bb debug`) |
| `shareVotes` | `true` | Save your own votes for Build Battle Wrapped |
| `toasts`, `chatMessages` | `true` | Achievement toasts and chat lines |

**Tuning game detection:** Hypixel's exact chat wording isn't documented, so `patterns.json` holds best guesses.
Turn on `/bb debug`, play a game or two, and the log shows the real lines to fix the patterns with.

## Admin: votes

Set `ADMIN_TOKEN` in `.env` (a long random string), restart, and open `/admin.html`. It shows how every mod user
votes and who they vote for. Only votes cast by people running the mod are known: Hypixel never reveals other players' votes.

## Design docs

- Architecture plan: https://claude.ai/artifact/NCbTPpibjoCtzUcMMAh9gp
- Profile page mockup: https://claude.ai/artifact/GaD1XVZ2wTdLs18ofSRcSE

(Both links are private until you share them.)

# VerityAI 2.0

AI assistant "Verity" for Paper 1.21.8 servers, powered by OpenRouter.

## Build
```
mvn clean package
```
Jar output: `target/VerityAI.jar`

## Install
1. Copy `VerityAI.jar` into `plugins/`.
2. Optionally install PlaceholderAPI / Vault / LuckPerms / EssentialsX first if
   you want those integrations (all optional — VerityAI works without them).
3. Start the server once to generate `plugins/VerityAI/config.yml`.
4. Set `ai.api-keys` to your OpenRouter key(s) and adjust `ai.model` if needed.
5. Fill in `server-info.*` (name, rules, IP, website, gamemodes) so Verity can
   answer questions about your server.
6. `/verity reload`.

## Usage
```
@verity how can I find diamonds?
```
or start hands-free conversation mode:
```
/verity chat
```
(say "bye" or run `/verity chat` again to stop).

## Commands
| Command | Description | Permission |
|---|---|---|
| `/verity reload` | Reload config.yml | `verity.reload` |
| `/verity clear [player]` | Clear conversation memory | `verity.use` / `verity.clear.others` |
| `/verity info` | Show plugin status | `verity.info` |
| `/verity debug` | Toggle debug logging | `verity.debug` |
| `/verity toggle\|on\|off` | Enable/disable Verity globally | `verity.toggle` |
| `/verity chat` | Toggle hands-free conversation mode | `verity.use` |
| `/verity personality <name>` | Switch personality preset | `verity.personality` |
| `/verity owner [player]` | View/set the server owner | `verity.owner` |
| `/verity map` | Show an in-chat mini-map around you | `verity.map` |
| `/verity stats` | View Verity's usage dashboard | `verity.stats` |
| `/verity model [name]` | View/switch the primary AI model | `verity.model` |
| `/verity task add\|remove\|list` | Manage your personal reminders | `verity.task` |
| `/verity quest` | Get a generated quest suggestion | `verity.quest` |
| `/verity tutorial <topic>` | Ask for a step-by-step tutorial | `verity.use` |
| `/verity feedback <good\|bad> [correction]` | Record feedback for Verity | `verity.feedback` |

## v3.3 additions (world tools, memory, tasks, quests, events, feedback)
- **Categorized long-term memory**: facts are now tagged (interest, friend, project,
  playstyle, goal, or general) — shown grouped in the system prompt, and settable via
  the extended `[REMEMBER: category | fact]` text tag or the `remember_fact` function's
  new `category` parameter.
- **More world tools**: nearest stronghold, current weather, current biome, and
  dimension (Overworld/Nether/End) — available as keyword-triggered context and as
  function-calling tools (`get_nearest_stronghold`, `get_environment`).
- **Crafting assistant**: `get_craftable_items` (function-calling only) scans the
  player's inventory against every registered shaped/shapeless recipe and lists what
  they can craft right now.
- **Reminders**: `/verity task add <HH:mm> <message>` sets a personal daily reminder
  (`/verity task list` / `remove <id>`); fires once a day if the player is online at
  that minute. No offline mail/queue — a lightweight in-game nudge, not a full
  scheduler.
- **Quest generator**: `/verity quest` asks the AI for one short, concrete objective
  based on the player's live context (location, held item). Text-only and one active
  quest at a time — there's no automatic progress tracking (e.g. counting items
  collected), which would need hooking every relevant game event and is a bigger
  feature on its own.
- **Event reactions**: Verity reacts to player join/death, defeating a boss
  (Ender Dragon/Wither/Warden/Elder Guardian), and raid start/victory with a short,
  config-editable message (`event-reactions.*`) — canned templates, not a live AI call,
  so reactions stay instant and free.
- **"What happened today?"**: a capped, in-memory (not persisted) log of today's public
  chat lets Verity give a real summary when asked, instead of guessing.
- **AI tutorial shortcut**: `/verity tutorial <topic>` is a convenience wrapper that
  asks Verity for a step-by-step tutorial on demand.
- **Feedback / lessons learned**: `/verity feedback bad <correction>` (staff-only by
  default) adds a note to a small, persisted, always-injected "avoid repeating these
  mistakes" list — the practical, bounded version of "the AI learns from its errors"
  (there's no actual model training happening).
- **Builder/Redstone help**: no dedicated feature was added for these — they're already
  handled well by normal `@verity` chat, now nudged to structure build answers with
  dimensions/materials/steps. Generating an actual `.litematic` schematic file is out
  of scope for this plugin (would need a WorldEdit/Litematica dependency); Verity gives
  a text plan instead.
- Not implemented: having Verity physically interact with the world (e.g. opening a
  chest) — that's not something a chat-driven assistant can safely do without a much
  larger, more invasive permission model, so it's intentionally left out.

## v3.2 professional features
- **Real AI conversation summarization**: the lightweight condensed note (v3.1) now
  gets upgraded to a proper 2-3 sentence AI-generated summary once it grows large,
  instead of just truncating from the front — runs in the background so it never
  delays a reply.
- **Smart long-term memory**: with `ai.embeddings.enabled`, facts are deduplicated by
  MEANING (cosine similarity) instead of just text overlap, and only the facts most
  relevant to the player's current question are injected (`memory.relevant-facts-limit`)
  instead of dumping everything known about them — falls back to the v3.1 behavior if
  embeddings are off or a call fails.
- **Real function calling**: `ai.function-calling.enabled` lets supporting models call
  structured tools (`get_player_status`, `get_nearby_terrain`, `get_server_time`,
  `remember_fact`, `run_command`) instead of the [[CMD:...]]/[REMEMBER:...] text tags —
  more reliable, and every tool still goes through the exact same safety checks
  (command whitelist, memory dedup/cap) as before.
- **Per-world personality**: `personality.per-world` lets different worlds use a
  different preset (e.g. a stricter tone in a minigame world), falling back to the
  globally active preset elsewhere.
- **Permission tiers**: `permissions.tiers` lets specific permission nodes (VIP, staff,
  etc.) override the model/cooldown/rate-limit/token-budget for just those players —
  checked in order, first match wins, `/verity model [name]` for a quick global switch.
- **Multi-model support**: builds on the existing fallback-model chain — combined with
  permission tiers, different player groups can now also get different primary models.
- **Semantic memory search**: same embeddings feature as above also powers relevance
  ranking, so long-term memory scales to a lot of stored facts without bloating the
  prompt.
- **In-game stats dashboard**: `/verity stats` (permission `verity.stats`) shows total
  requests, success/failure counts, average response time, tokens used, AI-run
  commands, and top askers since the last restart.
- **Public API for other plugins**: `com.verityai.api.VerityAIApi`, obtained via
  Bukkit's ServicesManager, lets other plugins ask Verity a question, read/add
  long-term memory facts, and check the active personality/model.
- **Plugin hook events**: `com.verityai.api.events.VerityQuestionEvent` (cancellable,
  question can be rewritten), `VerityAnswerEvent` (answer can be rewritten before
  delivery), and `VerityCommandExecutedEvent` (informational) — standard Bukkit events
  any plugin can listen for.

## v3.1 bugfixes / quality pass
- **Conversation memory**: short-term history is now cleared on `PlayerQuitEvent`,
  auto-purged after `memory.idle-clear-minutes` of inactivity, and turns dropped off
  the short-term window are rolled into a small condensed summary instead of being
  lost outright.
- **Long-term memory performance**: facts now live in an in-RAM cache (no more
  opening a YAML file on every single request); writes are batched and flushed every
  `memory.autosave-interval-minutes`, plus once on shutdown, so nothing is lost.
- **No more duplicate memories**: near-duplicate facts (same text, just reworded/
  recased) are skipped, and each player is capped at `memory.long-term-max-entries`.
- **Context sent to the AI**: consecutive identical messages from a player are no
  longer re-added to history (spam guard), and `memory.max-context-chars` caps the
  total history size per request (oldest turns trimmed first, freshest always kept).
- **API reliability**: transient errors (HTTP 429 / 5xx) are now retried on the same
  key/model with backoff (`ai.max-retries-per-key`) before moving to the next
  key/model; timeouts are configurable (`ai.connect-timeout-seconds`,
  `ai.request-timeout-seconds`); every failure is logged with its status code.
- **Thread safety**: `ConversationManager` no longer synchronizes the whole class on
  every call (which serialized unrelated players against each other) — locking is now
  per-player, and long-term memory uses copy-on-write lists instead of broad locks.
- **History logger**: transcripts now rotate daily and old files are deleted after
  `memory.history-retention-days` (0 disables cleanup); logging is still toggleable
  via `memory.save-history`.
- **Profanity filter**: normalizes spacing/punctuation tricks ("b.a.d", "b a d")
  without breaking normal sentences, uses word boundaries for Latin text to reduce
  false positives, supports Persian/English, and now reads `moderation.blocked-words`
  live so `/verity reload` actually picks up edits.
- **Rate limiter**: per-player limits are unchanged, plus a new server-wide
  `limits.global-max-requests-per-minute` and `limits.max-concurrent-requests` so a
  burst of players can't overload your OpenRouter budget or the server itself.
- **Config**: `/verity reload` now hot-reloads everything (rate limits, profanity
  list, memory limits, timeouts, etc.) without needing a restart, and out-of-range
  values fall back to sane defaults with a startup warning instead of misbehaving.

## Phase 3 additions
- **Per-player long-term memory ("database-like")**: `plugins/VerityAI/memory/<uuid>.yml`
  holds a capped list of facts about each player. With `memory.auto-remember: true`
  (default), Verity can tag things worth remembering (e.g. a player's name) at the end
  of its own replies; VerityAI saves and hides those tags automatically. Facts persist
  across restarts and are re-injected on every conversation.
- **Finglish support**: set `chat.language: finglish` to make Verity always answer in
  Persian written with Latin letters (e.g. "salam, chetori?"), alongside the existing
  `fa`, `en`, and `auto` options.
- **Config-taught knowledge**: `ai.custom-knowledge` is a list of facts/instructions in
  config.yml that are always injected into Verity's system prompt — a simple way to
  "teach" Verity server-specific info without touching code.
- **Asker tagging**: every reply now starts with `@PlayerName` (highlighted) so it's
  always clear who Verity is responding to; anyone else `@mentioned` in the reply gets
  a short notification sound.
- **AI-triggered game commands** (`commands.enabled`, off by default): Verity can run a
  real command when a player explicitly asks for one, but only from an admin-defined
  whitelist, and — for ordinary players — always executed *as that player*, so Bukkit's
  existing permissions still apply and Verity can never grant more than the player
  already has. An optional `owner-only-console-whitelist` allows a few elevated,
  console-run commands for the single configured owner only.
- **Owner setting**: `/verity owner [player]` views/sets `server-info.owner` in
  config.yml; Verity is told when it's talking to the owner, and owner-only command
  privileges (above) key off this name.
- **Map viewing**: `/verity map` renders a simple biome-colored ASCII mini-map around
  the player directly in chat; asking Verity about "the map" also hands it a real
  terrain summary instead of letting it guess. `integrations.map-web-url` can point to
  a Dynmap/BlueMap link shown alongside the mini-map.

## What's implemented (Phase 1 + 2)
- OpenRouter chat completions, streaming (SSE), multi-API-key and
  fallback-model retry chain.
- Configurable personality presets (default/funny/formal/guide/admin) + custom
  prompts, switchable live.
- `@verity` trigger + hands-free "conversation mode" for multi-turn chat.
- Short-term memory (per player, in RAM) and optional long-term memory
  (persisted facts per player under `plugins/VerityAI/memory/`).
- Full transcript logging (`plugins/VerityAI/history/`) and debug/error logs
  (`plugins/VerityAI/logs/`).
- Cooldown, requests-per-minute, and daily token budget per player, plus a
  "please wait" lock while a request is in flight.
- Simple blocked-word moderation filter (applied to both input and output).
- Context injection: player name/coords/world/TPS/online count/ping/time,
  held item, and (keyword-triggered) nearest village / nearest player /
  nearest desert biome, all pulled from live game data rather than guessed
  by the model.
- Soft integrations (auto-detected, degrade gracefully if absent):
  PlaceholderAPI (parses placeholders inside the personality/server-info
  text), Vault (balance), LuckPerms (primary group), EssentialsX (home list).
- Colored chat responses, `@player` mention highlighting, action-bar
  "Verity is thinking..." indicator during streaming, debug line showing
  model + response time.

## Explicitly out of scope for this build (would need separate follow-up work)
- **Discord bot integration** — needs a JDA dependency and its own bot
  process/token, distinct enough to be its own project.
- **Request queueing system / response caching** — the per-player in-flight
  lock + rate limiter cover spam protection; a full queue/cache layer is a
  bigger addition best done once real usage patterns are known.
- **Internal event API for third-party plugin developers** — straightforward
  to add later (a handful of Bukkit events) once the core is stable.

## Notes on Paper API specifics
`World#locateNearestStructure(Location, Structure, int, boolean)` and
`World#locateNearestBiome(Location, int, Biome...)` (returning
`StructureSearchResult` / `BiomeSearchResult`) are the current-generation
Paper/Bukkit APIs used here, verified against the 1.21.8 javadocs. If you
build against a different Paper version, double check these two signatures
first — they've changed a few times across 1.19–1.21.

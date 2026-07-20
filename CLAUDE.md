# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Poppy is a Paper (Minecraft server) plugin written in Java 25, built with Gradle Kotlin DSL. It targets Paper API 26.2 and provides homes/spawn/teleport/social commands (`/sethome`, `/home`, `/tpa`, `/back`, `/rtp`, `/trash`, `/afk`, etc.).

## Commands

- Build: `./gradlew build`
- Run a local Paper test server with the plugin loaded: `./gradlew runServer` (downloads/caches Paper 26.2 via the `xyz.jpenilla.run-paper` plugin; JVM heap is fixed at `-Xms2G -Xmx2G` in `build.gradle.kts`). Stop the server with the usual `stop` console command.
- Clean cached Paper jars if a test server misbehaves: `./gradlew cleanPaperCache` / `./gradlew cleanPaperPluginsCache`
- There is no test suite in this repo — verification is via `runServer` and manual in-game testing.

## Architecture

**Single plugin class, manual wiring.** `Poppy.java` (`onEnable`) is the composition root: every manager, GUI, and command is constructed and wired together by hand in one place, in a specific order (e.g. `TpaManager` is built after `Messages` because it needs it to send expiry notifications). When adding a new command/manager/listener, follow the existing pattern in `onEnable` — construct the manager, construct the command with its dependencies injected via constructor, register the executor/tab-completer/listener.

**Layers:**
- `commands/` — one class per command, all extending `SafeCommand` (`util/SafeCommand.java`). `SafeCommand.onCommand` is `final` and wraps `execute(...)` in a try/catch so an uncaught exception never leaks a stack trace to a player — it logs server-side and sends `general.error` instead. New commands must implement `execute(...)`, never override `onCommand`.
- `manager/` — stateful business logic and Bukkit listeners (e.g. `HomeManager` for home persistence/cache, `TeleportManager` for warmup/cancel logic, `TpaManager`, `CombatManager`, `BackManager`, `ShareManager`, `AfkManager`). Several managers implement `Listener` directly rather than having a separate listener class (`TeleportManager`, `TpaQuitListener` pattern is the exception, not the rule — check each manager individually).
- `gui/` — inventory-based GUIs (homes list, confirm delete/overwrite, trash). Each GUI has a paired `*Holder` (implements `InventoryHolder`, tags the inventory so the listener can identify it) and is driven by a listener class in the same package (e.g. `HomesGUI` + `PoppyHomesHolder` + `HomesGUIListener`).
- `listeners/` — standalone event listeners not owned by a specific manager (join/quit messages, tab-list health, unknown-command message).
- `model/` — just `Home`, an immutable record reused for both player homes and the server spawn (see `SpawnManager`).
- `util/` — `Messages` (loads `messages.yml`, supports `&`-color codes and `{placeholder}` substitution, falls back to showing the raw key if a message is missing rather than failing), `SafeCommand`, `PlayerNameSuggestions` (tab-completion helper), `PoppyStats` (in-memory session counters logged on shutdown).

**Persistence.** No database — everything is YAML under the plugin's data folder. Homes are one file per player at `plugins/Poppy/homes/<uuid>.yml`, loaded lazily into an in-memory cache on first access and evicted on quit (`HomeCacheListener` → `HomeManager#unload`). Writes are async (`HomeManager#save`) except on quit/shutdown, which write synchronously so nothing is lost; a per-player lock (`writeLocks`) prevents an async and a sync write from racing on the same file. Config (`config.yml`) and messages (`messages.yml`) are the standard Bukkit `saveDefaultConfig`/`saveResource` pattern — see those files for every tunable (warmup timers, cooldowns, expiry durations, feature toggles).

**Threading.** Bukkit/Paper is single-threaded for game state: all manager caches assume they're only touched from the main thread. The only intentional off-thread work is the async YAML write in `HomeManager#save` — don't add new async paths that touch Bukkit API objects (`Player`, `Location`, `World`, etc.) without scheduling back onto the main thread.

**Ephemeral state has a quit/expiry story.** Short-lived maps (`TeleportManager`'s pending warmup tasks, `ShareManager`'s share tokens, `TpaManager`'s pending requests, `CombatManager`'s tags) either self-expire via a scheduled task or are cleaned up by a dedicated quit listener (e.g. `TpaQuitListener`, `HomeCacheListener`, `BackListener`). When adding new per-player state, decide up front how it gets removed — don't let a map grow unbounded across player sessions.

**Permissions & commands** are declared in `src/main/resources/plugin.yml` (must be kept in sync with the `commands/` classes) and gated per-command via `poppy.<command>` permission nodes, aggregated under `poppy.*`.
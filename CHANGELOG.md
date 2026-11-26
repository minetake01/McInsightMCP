# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0-beta.4] - 2026-11-26

### Added
- `recipes_render` (`GET /api/v1/recipes/render`) and the `mc_render_recipe` tool: the mod draws a recipe with the viewer's own renderer (JEI's recipe layout drawable, EMI's widgets, REI's display renderer) into an off-screen target and answers a base64 PNG, so an assistant can show the machine or crafting GUI instead of only listing its slots. The MCP result carries one image block per recipe beside the same JSON envelope (the picture bytes appear only in the image block), bounded by `limit` (`1..8`) and `scale` (`1..3`); a category the viewer cannot draw is reported per recipe under `missing`, and nothing drawable is an error rather than an empty success
- `ViewerModel.Recipe` keeps the viewer's own recipe object (`nativeRecipe`) so the adapter that produced a recipe can paint it; it is never serialized and no query semantics read it
- E2E: the rendered PNG is written to the scenario's run directory, and every active viewer must draw through its own category

### Changed
- The MCP server exposes twelve tools; the other `mc_*` names are unchanged
- The README's "no screen capture" claim still holds and now says why: the diagram is drawn from the recipe layout, never cropped from the display. On `26.x`, where the engine's GUI renderer only targets the main render target, that target is cleared and used as a scratch surface between frames: only the recipe rectangle is read, and the frame that follows clears it before drawing. Each requested image costs one short client-thread task (one drawing per frame, PNG encoding off the render thread), so several images never land on one frame
- REI painting is `1.21.x` only: its `26.x` display-renderer API is not on this build's classpath, so a `26.x` REI client lists recipes but does not draw them
- The e2e runner runs scenarios in parallel (`--jobs`, default one per four hardware threads, at most four), prints each job's checks under a `<version>/<loader>/<scenario>` prefix, and reports how long each scenario and the whole run took
- The e2e runner no longer waits 30 s for a client that ignores `stop`: it closes the client's window (a `WM_CLOSE` on Windows, `SIGTERM` elsewhere) and only kills what outlives its deadline, so every scenario ends in a second or two
- The e2e harness keeps a local run out of the way: it hands the system cursor back whenever a client captures it, and sends the game window to the taskbar, so the run neither traps the mouse nor covers the desktop (a headless CI client keeps its window on the virtual display)

## [0.1.0-beta.3] - 2026-11-26

> **Breaking internal response-shape change (beta, no compatibility shim).** Endpoints, accepted
> parameters, page fields, error codes, and viewer metadata changed together. This project is still in
> beta and there is deliberately no shim for the previous shapes: update clients and tools instead.
> The 11 MCP tool names are unchanged.

### Added
- Minecraft `26.1.2` and `26.3` builds alongside `1.21.1` (one JAR per version and loader, `mcinsight-<loader>-<mc_version>-<version>.jar`), with `mod/gradle.properties` `minecraft_versions` as the single list, `mod/stonecutter.properties.toml` as the per-version pins, and `game/Versioned` answering each renamed Minecraft entry point once
- A single endpoint table (`mod/.../api/InsightEndpoint.java`) declaring, for every endpoint, its id, HTTP path, data scope, player scope, time budget, accepted parameters, and page bounds; unknown parameters and out-of-bounds `limit` values are rejected instead of ignored or clamped
- A single error catalogue (`mod/.../api/InsightError.java`) whose entries carry the HTTP status, so `400/401/404/405/409/429/500/502/503/504` now mean what they say; `RATE_LIMITED`, `SERVER_MOD_REQUIRED`, `VIEWER_UNAVAILABLE`, `REMOTE_ERROR`, and `REMOTE_TIMEOUT` are part of it
- One paginated response shape (`Page`) reporting `offset`, `totalKnown`, `total` (only when counted), `returned`, and `hasMore`, with domain array keys `results`, `members`, `guides`, `hits`, `entries`, `recipes`, `trades`, `advancements`; viewer pages report `totalKnown: false`
- `PartialData` (`{partial: true, missing: [{part, code, error}]}`) so a composite answer states which part could not be read instead of returning a thinner one
- A shared `ViewerModel` with JEI, EMI, and REI adapters over it, and one `ViewerQuery`/`ViewerJson` implementation for filtering, paging, and JSON shaping, so all three viewers answer identically (`slotGranularity: slots|flat`, slot alternatives, catalysts, workstations; ingredient types `item`, `fluid`, `custom:<kind>`)
- A packet-bridge design where `InsightDispatcher` decides who can answer an endpoint (`DataScope`, `PlayerScope`) and transparently delegates server-owned data; the same dispatcher and `Params` validation serve HTTP and bridged requests
- `mcp-server/src/api/` mirroring the mod's endpoint table, error catalogue, result envelope, and wire types, plus `tests/contract.test.ts`, which reads the Java declarations and fails when the client copy drifts
- A restructured MCP server: `api/` (contract mirror), `data/` (one module per data kind), `jar/`, `bytecode/`, `decompiler/`, `metadata/`, and `parsers/retention.ts` for the one `rawJson` retention rule
- `trades_query` accepts `q` as free text in addition to the exact-id/tag `item` filter
- `maxSyncBlockRadius` config key (default `16`, maximum `16`) bounding block-entity contents sent over the multiplayer bridge
- End-to-end tests in real Minecraft (`mcp-server/e2e`, `pnpm e2e`): NeoForge and Fabric × singleplayer, multiplayer with the server mod, and multiplayer without it, driven through MCP; presets `quick`/`multiplayer`/`release`, `--suite` selection, and a local-only `--extra-mods`. CI runs `quick` on pull requests and `release` before publishing

### Changed
- The mod builds one node per (loader, Minecraft version) with Stonecutter: `mod/gradle.properties` lists `minecraft_versions`, each version's pins live under `mod/stonecutter.properties.toml`, and `:common:<version>` / `:neoforge:<version>` / `:fabric:<version>` replace the old `:common` / `:neoforge` / `:fabric` projects. Jars are named `mcinsight-<loader>-<mc_version>-<version>.jar` under `mod/<loader>/versions/<mc_version>/build/libs/`, and the e2e runner gained `--version` with `quick` running the newest version only
- Plugin versions are declared once, in `pluginManagement`, so a branch script names a plugin and nothing else; the pins that the old single-version build needed and nothing reads (`loomx.loom_version`, `mod.mc_releases`) are gone, and each node publishes one capability instead of two coordinates, one of which dropped the branch and version it was built for
- Supported Minecraft versions are `1.21.1`, `26.1.2` and `26.3`; the mod JARs now declare exactly the version they were built for and nothing else, since that is the only version they are built and tested against
- A name that changed between versions is substituted by a Stonecutter replacement and a changed shape is a `//? if` at the call site, so the sources keep 1.21.1's names; the ones that must be answered in Java are in `game/Versioned`. The trade system was replaced rather than renamed, so `trade/TradeCollector` carries one reader per trade subsystem and only the matching version compiles it
- EMI integration is compiled only where its dependency is pinned. It has no `26.x` release, so the `26.x` builds carry no EMI bridge and no EMI entrypoint, and it returns as soon as a `deps.emi` pin is added for a version
- `item=` (and every id argument) now accepts only a full `namespace:path` id or a `#namespace:tag` tag; substring and display-name matching is gone, and names are resolved through `registry_search` first. Recipes without structural I/O are matched by exact resolved ids in their encoded JSON
- Data origin is no longer part of any response: the authoritative-server-data flag, the flattened JEI info field, and the registry-versus-resource-manager source marker are replaced by plain values plus `viewerInformation: {<viewerName>: string[]}`
- Pagination is uniform across endpoints (previously each collector reported its own field set), and `recipes_query`'s effective page ceiling can be lowered by `maxRecipeResults`
- `advancements_query` is server data: definitions and progress always come together from the world owner, and every entry carries `progress`; a client whose server does not run McInsight gets `SERVER_MOD_REQUIRED` instead of a definitions-only page
- `recipes_query` puts viewer pages under `viewers: {<name>: page}` instead of beside `ok`/`vanilla`, and `source` accepts any registered viewer name (an unregistered one is `INVALID_SOURCE`); an unknown `role` is `INVALID_PARAMETER` instead of meaning `any`
- `trades_query` takes `side=cost|result` so the trade direction is the mod's filter; `mc_query_item_flow` uses it, which makes the trade page's `total`/`returned`/`hasMore` describe what is returned
- `datamaps_query` takes `item=` with the shared `ItemFilter` rule (value keys only), and `mc_query_item_flow` uses it instead of a free-text `q`
- `mc_query_world_data` rejects a parameter the chosen `kind` does not read, and `mc_query_item_flow` rejects a named source that cannot answer (`loot` with `direction: use`, `viewer_info` for a `#tag`) instead of returning a `{skipped}` placeholder
- Guide list/search answers report `missing: [{part: "datapack_guides"}]` when datapack-shipped books are unreadable in the session
- Bridged requests wait the endpoint budget plus a transport margin, so a host that answers at its deadline is still heard
- Packet transport moved out of the platform helper into `InsightNetworking`, which both loaders bind once; the client keeps the endpoint set the host announces and fails `SERVER_MOD_REQUIRED` for one the host does not serve
- `status` always reports `languageCode` (the client's language) and no longer reports `isSingleplayer`, which was identical to `serverActive`
- Trade listings report `sampled` for a randomized offer and a reflected `listing` object for one that cannot be materialized, so both facts are named after what the mod actually emits
- `loot_query` pages only its loot tables: `entries` carries the `offset`/`totalKnown`/`total`/`returned`/`hasMore` envelope, while `lootModifiers` is returned whole with `totalModifiers` (the active modifier set is the small list the pack layers name, so slicing it with the table page's offset would make paging tables shift the modifier window)
- `mc_query_item_flow` runs each source through a handler whose failure becomes a `missing` entry (`partial: true` with the mod's code), so a failed part is reported rather than omitted and a successful part is never dropped
- Multiplayer server data is fetched transparently when the server also runs McInsight; when it does not, the endpoint fails with `SERVER_MOD_REQUIRED` instead of degrading quietly, and a client hosting an integrated server always answers locally
- The MCP server reads its version from `package.json` through `version.ts` instead of a hand-maintained copy in `server.ts`
- Operator caps are now applied inside the collectors (`config/Limits` read by `AreaScanner`, `TargetCollector`, `BlockEntityPart`, `InsightEndpoint`) instead of only in the loopback HTTP handler, so a bridged request is bounded by the same numbers as a local one
- Native parser tier no longer claims registries/tags: registry data arrives structured from the mod. The native tier covers vanilla recipes, worldgen, biome modifiers, loot tables, and Global Loot Modifiers; the built-in mod tier keeps its fail-closed `modVersionRange` gating; both share one `hasUnparsedFields` envelope
- Documentation restructured around the contract (endpoint table, error catalogue, response shapes, package map, transports) in `docs/ARCHITECTURE.md`; READMEs and CONTRIBUTING updated to match
- Build toolchain: Gradle `9.9.2` (daemon on JDK 25 via `gradle-daemon-jvm.properties`; the mod compiles for Java 21 on `1.21.1` and Java 25 on `26.x`), Fabric Loom `1.19.4` (`net.fabricmc.fabric-loom-remap` for `1.21.1`, `net.fabricmc.fabric-loom` for the unobfuscated `26.x`) with its provider-based run API, Fabric Loader `0.20.1`, Parchment `1.21.1` `2024.11.17` (`1.21.1` only), JUnit `6.1.3`; Fabric API, JEI, EMI and REI are pinned per Minecraft version in `mod/stonecutter.properties.toml`. Every third-party Maven repository is restricted to the groups it serves, and build caching and parallel execution are on
- Fabric e2e runs load the same JEI build the mod compiles against, instead of a version pinned to what Loom 1.8 could remap
- The mod reports its version from the loader's mod metadata (`IPlatformHelper.getModVersion()`), so `mod.version` in `mod/stonecutter.properties.toml` is its only declaration
- MCP server: Node.js `>= 22.12` (Node 20 is end-of-life; CI tests 22 and 24), pnpm pinned through `packageManager`, `@types/node` held at the lowest supported Node, and `tsc` typechecks the tests too, with `noUncheckedIndexedAccess`, `noImplicitOverride`, `noFallthroughCasesInSwitch`, and `verbatimModuleSyntax`. The build emits only the JavaScript the CLI runs

### Removed
- The per-collector "no data" codes for loot, worldgen, data maps, recipes, and resources, plus the old advancement-progress and server-data codes: one `DATA_UNAVAILABLE` and one `SERVER_MOD_REQUIRED` now describe every one of those conditions
- The authoritative-server-data flag, the flattened JEI info field, and the registry-versus-resource-manager provenance marker
- Per-endpoint differing pagination fields, and `limit` clamping in place of an error
- The loot-modifier "more available" flag: loot modifiers are no longer paged alongside the table page, so nothing reports a second `hasMore` for them
- The `net.minetake.mcinsight.server` and `introspect` packages: replaced by `api/`, `game/`, `cache/`, `data/`, `serial/`, `metrics/`, `http/`, `network/`, and per-concept packages (`recipe/`, `registry/`, `guides/`, `loot/`, `datamaps/`, `worldgen/`, `world/`, `advancement/`, `trade/`, `status/`, `player/`, `reflect/`)
- `McInsightConstants.MOD_VERSION`, the unused Mixin/MixinExtras compile dependencies and Sponge repository left from the mod template, the never-registered resource-invalidator hook in `CacheGenerations`, and the unused `tsx` dev dependency
- The Dependabot hold on Gradle 9
- The mod JAR's `pack.mcmeta`: the JAR ships no `assets/` or `data/`, so it declared a pack format for a pack with nothing in it, and that number differs on every Minecraft version
- The stale bench parameters that the new contract rejects (`item=` as a substring, per-run `cantKeepUp` accounting from a server handle the harness never had). Every benchmarked URL now resolves in `InsightEndpoint` with only that endpoint's declared parameters and in-bounds `limit` values

### Fixed
- An HTTP request that hit a linkage error (an optional integration's API differing from the one compiled against) never got an answer and left the caller waiting; it now fails `INTERNAL_ERROR`. The JEI bridge no longer calls `getSourceRecipeIds` on JEI older than 19.64, where it does not exist
- `trades_query` is server data: modded trades are registered from the server's datapack reload, so a multiplayer client's own copy is not the world's
- A malformed `item=` is `INVALID_ID` even when the endpoint's data lives on a server that cannot answer
- Loot tables were unreadable on an integrated server: `MinecraftServer.registryAccess()` holds only a placeholder for the reloadable layer, so registries are now read from `reloadableRegistries()`, which holds every layer
- `trades_query` timed out on the server thread: vanilla wraps cartographer treasure maps in a biome-specific wrapper, which hid the world search from the check, so each map listing searched for a structure per villager type. Wrappers are unwrapped first and listings are recognised by the values they hold (a structure tag, a villager-type map) instead of by class name
- `registry_inspect` no longer answers without default components when the game thread does not hand over the registries; it fails with `DATA_UNAVAILABLE`
- The MCP client no longer turns a failed mod-version lookup into "versions unknown", which surfaced as a parser version mismatch
- Benchmark URLs that would now be rejected with `400 INVALID_PARAMETER`: `recipes/query` substring item queries were replaced with exact-id and percent-encoded `#tag` queries, and all paths/parameters were checked against `InsightEndpoint.allowedParams()`
- `describeBuiltinParsers`/`mc_query_recipes` viewer handling no longer assumes a fixed viewer name set; buckets and availability come from whatever registered
- Version synchronization instructions: the MCP server version is read from `package.json`, so `server.ts` is no longer a version declaration to keep in sync
- Recipe JSON encoding cached in the shared recipe index is filled concurrently by HTTP and bridge threads; the cache was a plain `HashMap` and is now concurrent
- The e2e workflow's client could not start on the Linux runner for `26.x`: RenderPearl found no GLX visual it accepts for OpenGL and no Vulkan driver implementing `VK_KHR_surface`, so the scenario never reached player-ready. The runner now installs Mesa's lavapipe, and the client renders through the Vulkan backend

## [0.1.0-beta.2] - 2026-11-24

### Security
- Bumped `adm-zip` from `0.5.18` to `0.6.1` in `mcp-server` (resolving `CVE-2026-39244`) and removed redundant `@types/adm-zip`

### Changed
- Updated Gradle build toolchain (`net.neoforged.moddev` `2.0.148`, `mixinextras-common` `0.5.5`, `foojay-resolver-convention` `1.0.0`)
- Updated `mcp-server` dev dependencies (`typescript` `7.0.2`, `vitest` `5.1.1`)
- Shortened README section headers to satisfy Modrinth description length guidelines and added a CI check for heading length
- Configured Dependabot ignore rules for `gradle-wrapper >= 9.0.0` (pending Fabric Loom support) and `@types/node >= 25.0.0`

## [0.1.0-beta.1] - 2026-11-24

Initial public beta release of McInsightMCP.

### Added
- Minecraft `1.21.1` multiloader read-only introspection mod (`common`, `neoforge`, `fabric`) with loopback HTTP API (`127.0.0.1:25580..25599`)
- Optional integrations for JEI, Patchouli, GuideME, FTB Quests, Create Ponder, Oracle Index, and mod lang files
- MCP `2026-07-28` compliant TypeScript server (stateless Streamable HTTP & `stdio`) exposing 11 semantic tools (`mc_status`, `mc_player_state`, `mc_observe_world`, `mc_search_registry`, `mc_inspect`, `mc_query_item_flow`, `mc_query_recipes`, `mc_query_world_data`, `mc_query_advancements`, `mc_guides`, `mc_inspect_code`)
- Tier 1 native Minecraft parsers and Tier 2 version-scoped parsers for Create `6.x`, Mekanism `10.7.x`, AE2 `19.x`, Oritech `1.x`, Farmer's Delight `1.x`, Ars Nouveau `5.x`, and TofuCraftReload `12.x`
- On-demand JAR inspection, `<clinit>` bytecode `trace_id` tracing, and Vineflower `1.12.0` / `javap` decompilation
- Off-main-thread caching and two-phase world/player snapshot collection with performance benchmark harness (`bench/`)
- User-focused `README.md` / `README.ja.md`, separated `docs/ARCHITECTURE.md`, OSS community files (`CONTRIBUTING.md`, `SECURITY.md`, issue/PR templates, Dependabot), and GitHub Actions CI/Release workflows

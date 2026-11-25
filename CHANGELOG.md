# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

> **Breaking internal response-shape change (beta, no compatibility shim).** Endpoints, accepted
> parameters, page fields, error codes, and viewer metadata changed together. This project is still in
> beta and there is deliberately no shim for the previous shapes: update clients and tools instead.
> The 11 MCP tool names are unchanged.

### Added
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

### Changed
- `item=` (and every id argument) now accepts only a full `namespace:path` id or a `#namespace:tag` tag; substring and display-name matching is gone, and names are resolved through `registry_search` first. Recipes without structural I/O are matched by exact resolved ids in their encoded JSON
- Data origin is no longer part of any response: the authoritative-server-data flag, the flattened JEI info field, and the registry-versus-resource-manager source marker are replaced by plain values plus `viewerInformation: {<viewerName>: string[]}`
- Pagination is uniform across endpoints (previously each collector reported its own field set), and `recipes_query`'s effective page ceiling can be lowered by `maxRecipeResults`
- Advancement progress is reported as one `progressUnavailable` boolean plus a `missing` entry (part `progress`, code `SERVER_MOD_REQUIRED`) when the world owner cannot supply it, instead of a separate availability flag and reason string
- Trade listings report `sampled` for a randomized offer and a reflected `listing` object for one that cannot be materialized, so both facts are named after what the mod actually emits
- `loot_query` pages only its loot tables: `entries` carries the `offset`/`totalKnown`/`total`/`returned`/`hasMore` envelope, while `lootModifiers` is returned whole with `totalModifiers` (the active modifier set is the small list the pack layers name, so slicing it with the table page's offset would make paging tables shift the modifier window)
- `mc_query_item_flow` runs each source through a handler whose failure becomes a `missing` entry (`partial: true` with the mod's code), so a failed part is reported rather than omitted and a successful part is never dropped
- Multiplayer server data is fetched transparently when the server also runs McInsight; when it does not, the endpoint fails with `SERVER_MOD_REQUIRED` instead of degrading quietly, and a client hosting an integrated server always answers locally
- The MCP server reads its version from `package.json` through `version.ts` instead of a hand-maintained copy in `server.ts`
- Operator caps are now applied inside the collectors (`config/Limits` read by `AreaScanner`, `TargetCollector`, `BlockEntityPart`, `InsightEndpoint`) instead of only in the loopback HTTP handler, so a bridged request is bounded by the same numbers as a local one
- Native parser tier no longer claims registries/tags: registry data arrives structured from the mod. The native tier covers vanilla recipes, worldgen, biome modifiers, loot tables, and Global Loot Modifiers; the built-in mod tier keeps its fail-closed `modVersionRange` gating; both share one `hasUnparsedFields` envelope
- Documentation restructured around the contract (endpoint table, error catalogue, response shapes, package map, transports) in `docs/ARCHITECTURE.md`; READMEs and CONTRIBUTING updated to match

### Removed
- The per-collector "no data" codes for loot, worldgen, data maps, recipes, and resources, plus the old advancement-progress and server-data codes: one `DATA_UNAVAILABLE` and one `SERVER_MOD_REQUIRED` now describe every one of those conditions
- The authoritative-server-data flag, the flattened JEI info field, and the registry-versus-resource-manager provenance marker
- Per-endpoint differing pagination fields, and `limit` clamping in place of an error
- The loot-modifier "more available" flag: loot modifiers are no longer paged alongside the table page, so nothing reports a second `hasMore` for them
- The `net.minetake.mcinsight.server` and `introspect` packages: replaced by `api/`, `game/`, `cache/`, `data/`, `serial/`, `metrics/`, `http/`, `network/`, and per-concept packages (`recipe/`, `registry/`, `guides/`, `loot/`, `datamaps/`, `worldgen/`, `world/`, `advancement/`, `trade/`, `status/`, `player/`, `reflect/`)
- The stale bench parameters that the new contract rejects (`item=` as a substring, per-run `cantKeepUp` accounting from a server handle the harness never had). Every benchmarked URL now resolves in `InsightEndpoint` with only that endpoint's declared parameters and in-bounds `limit` values

### Fixed
- Benchmark URLs that would now be rejected with `400 INVALID_PARAMETER`: `recipes/query` substring item queries were replaced with exact-id and percent-encoded `#tag` queries, and all paths/parameters were checked against `InsightEndpoint.allowedParams()`
- `describeBuiltinParsers`/`mc_query_recipes` viewer handling no longer assumes a fixed viewer name set; buckets and availability come from whatever registered
- Version synchronization instructions: the MCP server version is read from `package.json`, so `server.ts` is no longer a version declaration to keep in sync

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

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- EMI (`1.1.24+1.21.1`) and REI (`16.0.799`) recipe viewer bridges alongside JEI, with multi-viewer `source` filtering (`all`, `vanilla`, `viewer`, `jei`, `rei`, `emi`) and unified `viewerIngredients`/`viewerInformation`
- Client-only loopback HTTP server (disabled on dedicated servers) with optional read-only multiplayer `CustomPacketPayload` sync (`mcinsight:hello`, `mcinsight:request`, `mcinsight:response`) strictly scoped to server registries, the requesting player's advancements, and nearby block entities (`<= 16` blocks)
- Unified `/api/v1/registry/inspect` endpoint (`auto`, `item`, `block`, `fluid`, `entity`), fluid raycasting (`includeFluids`), `#tag` recipe queries, Jar-in-Jar indexing, expanded pack script search (`scripts/`, `defaultconfigs/`, `saves/*/datapacks/`), client-language guide fallback (`en_us`), and experimental villager trade inspection (`listingDetails`, `isSample`)
- Java JUnit 5 unit tests (`:common:test`), expanded TypeScript test suite (`modVersion.test.ts` and multi-viewer / error-boundary coverage), and Windows + Node 20 CI matrix

### Changed
- `mc_query_item_flow` now aggregates sources via `Promise.allSettled` with explicit error reporting, directional trade filtering, and catalyst usage lookups
- `nativeParsers` and `modParsers` automatically preserve verbatim `rawJson` whenever unparsed fields remain (`hasUnparsedFields`) and enforce fail-closed version checks
- Pagination (`offset`) extended across `/api/v1/registry/tag`, `/api/v1/trades/query`, `/api/v1/advancements/query`, and `/api/v1/guides/*`
- Strict HTTP query parsing and full JSON error boundary in `InsightHttpServer` and `mcp-server` HTTP transport

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

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

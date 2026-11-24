# McInsightMCP Architecture & Protocol Specification

## 1. Repository Layout

```text
McInsightMCP/
├── docs/
│   └── ARCHITECTURE.md     # HTTP API contract, parser tiers, and MCP tool architecture
├── mod/                    # Multiloader Gradle project (common / neoforge / fabric)
├── mcp-server/             # TypeScript MCP 2026-07-28 server (Streamable HTTP & stdio)
└── bench/                  # Main-thread MSPT and endpoint latency benchmark harness
```

---

## 2. Design Principle: Stateless Read-Only Introspection + 2-Tier Mechanical Parsers

Modded Minecraft packs combine hundreds of mods, KubeJS overrides, datapacks, multiblock structures, and custom in-world mechanics that cannot be captured by a single rigid recipe schema. McInsightMCP separates runtime observation into two mechanical parser tiers plus direct primary-evidence access:

```text
Minecraft JVM (NeoForge / Fabric Client + Optional Server Sync)
  ├── McInsight Mod (Client-only HTTP on 127.0.0.1:25580..25599; dedicated server HTTP disabled)
  │     ├── Native Minecraft Systems (RecipeManager CODEC, Dynamic Worldgen, LootTables, Registries/Tags)
  │     ├── Recipe Viewer Bridges (JEI, EMI, REI -> categories, catalysts, typed/fluid/chemical ingredients, info)
  │     ├── ResourceManager + FTB Quests SNBT Scanner (Patchouli / GuideME / Ponder / Oracle / Lang auto+fallback)
  │     ├── Player / Environment / Raycast (blocks, fluids, entities) / Area Scanner
  │     ├── Optional Client↔Server CustomPacketPayload Sync (worldgen, loot, datamaps, own advancements, nearby block entities <= 16 blocks)
  │     └── Runtime Class Reflection Introspector
  ▼ Pure JDK Loopback HTTP JSON API (no GLFW / screen capture hooks)
TypeScript MCP Server (Node >= 20, @modelcontextprotocol/server v2.4, @modelcontextprotocol/node v2.1, Spec 2026-07-28)
  ├── Stateless Streamable HTTP (/mcp with Host/Origin localhost guards) & stdio (11 agent-semantic tools)
  ├── McHttpClient (parallel port auto-discovery 25580..25599)
  ├── Tier 1: Native Vanilla Parsers (mcp-server/src/parsers/nativeParsers.ts)
  ├── Tier 2: Version-Scoped Built-in Major Mod Parsers (mcp-server/src/parsers/modParsers/)
  └── Tier 3: Primary Evidence, Bytecode ID Tracer & Decompiler (adm-zip, Jar-in-Jar, pack script search, <clinit> field tracer, javap, Vineflower 1.12.0)
```

### 2.1 Three-Layer Observation Strategy

1. **Tier 1 — Native Minecraft Parsers (`mcp-server/src/parsers/nativeParsers.ts`)**:
   Mechanically normalizes Minecraft's built-in data formats (with automatic `hasUnparsedFields` lossless `rawJson` retention): vanilla crafting/smelting/stonecutting/smithing recipes (including 1.21 components and compound ingredients), dynamic worldgen (`configured_feature` ore veins, `placed_feature` placement modifiers, biome climate/spawners, structures, biome modifiers), loot tables & Global Loot Modifiers, and registries/tags.
2. **Tier 2 — Version-Scoped Major Mod Parsers (`mcp-server/src/parsers/modParsers/`)**:
   Built-in parsers for major mods (`create` 6.x, `mekanism` 10.7.x, `ae2` 19.x, `oritech` 1.x, `farmersdelight` 1.x, `ars_nouveau` 5.x, `tofucraft` 12.x), strictly gated fail-closed by `semver` `modVersionRange` against live loaded mod versions. When a mod version falls outside the tested range, the parser refuses to guess (`parserVersionMismatch`) and passes verbatim raw JSON to the AI. Parsed responses omit `rawJson` by default (`includeRaw=false`) and include it automatically when unparsed fields remain, mechanical parsing is unavailable, or `includeRaw=true`.
3. **Tier 3 — Primary Evidence & Bytecode Introspection**:
   For any other mod, multiblock, or undocumented in-world mechanic, the server exposes `Recipe.CODEC` raw JSON (with KubeJS/datapack changes applied), optional JEI/EMI/REI categories, catalysts, and info descriptions, BlockEntity NBT, loaded guides/quests (Patchouli, GuideME, FTB Quests, Create Ponder, Oracle Index, active-lang + `en_us` fallback lang files), runtime registry metadata (`blockInfo`, `fluidInfo`, `entityInfo`, `defaultComponents`), runtime class reflection, and on-demand JAR + Jar-in-Jar inspection, `<clinit>`/`ConstantValue` ID-to-field bytecode tracing (`trace_id`), and **Vineflower 1.12.0** / `javap` Java decompilation.

### 2.2 Transport, Security & Isolation Design

- **Zero Native Window / GLFW Dependencies**: The mod exposes structured runtime data over a pure JDK loopback HTTP server (`com.sun.net.httpserver.HttpServer` bound strictly to `127.0.0.1:25580..25599` on the client side only) and never touches GLFW or screen capture hooks.
- **Client-Only HTTP + Privacy-Bounded Server Sync**: Dedicated servers never open HTTP ports. When connected to a multiplayer server that also installs McInsight, the client queries server-side registries/loot/datamaps, the requesting player's own advancement progress, and block entity NBT within 16 blocks via allowlisted, rate-limited `CustomPacketPayload` channels (`mcinsight:hello`, `mcinsight:request`, `mcinsight:response`) — other players' data is never exposed. When server sync is absent, inaccessible endpoints fail honestly with explicit error codes (`SERVER_DATA_UNAVAILABLE`, `ADVANCEMENT_PROGRESS_UNAVAILABLE`, etc.).
- **Pure Read-Only & Stateless**: Neither the mod nor the MCP server mutates world state or maintains persistent server-side session state.
- **MCP `2026-07-28` Transport**: Built on `@modelcontextprotocol/server` v2.4 and `@modelcontextprotocol/node` v2.1, supporting stateless Streamable HTTP (`/mcp`, `/health`, with `localhostHostValidation()` and `localhostOriginValidation()`) and `stdio`.

---

## 3. Mod Local HTTP API (`/api/v1/...`)

All endpoints bind strictly to `127.0.0.1` on client instances using `com.sun.net.httpserver.HttpServer`. Every response is JSON. Errors return `{ "ok": false, "code": "...", "error": "..." }`. If `authToken` is configured in `config/mcinsight.json`, requests must send `Authorization: Bearer <token>`.

| Endpoint | Method | Description |
|---|---|---|
| `/api/v1/status` | GET | Mod version, MC/loader info, world state, language code, active integrations (`jei`, `emi`, `rei`, `remoteServerBridgeReady`), optional `includeMods=true` |
| `/api/v1/mods` | GET | Loaded mod list (`id`, `name`, `version`, `description`, `jarPath` including nested Jar-in-Jar paths) |
| `/api/v1/player` | GET | Player position, biome, live `environment`, vitals (`absorptionAmount`, `airSupply`, `experienceProgress`, `gameMode`, `vehicle`), main `inventory` (`0..35`), offhand/armor, open screen |
| `/api/v1/looking-at` | GET | Raycast block/fluid/entity target (`maxDistance`, `includeFluids`) |
| `/api/v1/scan` | GET | Nearby blocks, block entities, and entities (`radius`, `filter`) |
| `/api/v1/registry/search` | GET | Search registries (`kind=item\|block\|fluid\|entity\|tag\|item_tag\|block_tag\|fluid_tag\|entity_tag`, `q`, `mod`, `limit`, `offset`) |
| `/api/v1/registry/inspect` | GET | Detailed metadata for `kind=auto\|item\|block\|fluid\|entity` & `id=namespace:path` (`defaultComponents`, `blockInfo`, `fluidInfo`, `entityInfo`, `viewerInformation`) |
| `/api/v1/registry/tag` | GET | Paginated members of an `auto`/`item`/`block`/`fluid`/`entity` tag (`kind`, `id`, `limit`, `offset`) |
| `/api/v1/recipes/types` | GET | All `RecipeType` IDs + counts and JEI/EMI/REI category UIDs + catalysts |
| `/api/v1/recipes/query` | GET | Unified `RecipeManager` raw JSON + JEI/EMI/REI viewer extraction (`item`, `role`, `type`, `mod`, `source=all\|vanilla\|viewer\|jei\|rei\|emi`, `limit`, `offset`) |
| `/api/v1/worldgen/summary` | GET | Dynamic registry counts for dimensions, biomes, structures, placed/configured features, and biome modifiers |
| `/api/v1/worldgen/query` | GET | Worldgen & biome modifier definitions (`kind`, `q`, `mod`, `searchContent`, `minTemp`, `maxTemp`, `summaryOnly`, `limit`, `offset`) |
| `/api/v1/loot/query` | GET | `LootTable`s & NeoForge GLMs with exact-ID/tag reverse `item` lookup (`q`, `item`, `mod`, `includeModifiers`, `limit`, `offset`) |
| `/api/v1/datamaps/query` | GET | NeoForge `data_maps` JSON definitions & merged pack layers (`q`, `registry`, `mod`, `includeRaw`, `limit`, `offset`) |
| `/api/v1/trades/query` | GET | Registered `VillagerTrades` & `WanderingTrader` offers (`item`, `profession`, `mod`, `limit`, `offset`) |
| `/api/v1/advancements/query` | GET | Advancement definitions, triggers, rewards, and live player progress (`q`, `mod`, `status`, `includeRecipes`, `limit`, `offset`) |
| `/api/v1/guides/list` | GET | Paginated guide books (`assets/` + `data/`), quests, and lang docs (`source`, `mod`, `limit`, `offset`) |
| `/api/v1/guides/search` | GET | Case-insensitive search across guide/quest/ponder/oracle/lang entries (`q`, `source`, `mod`, `lang`, `includeAllLang`, `limit`, `offset`) |
| `/api/v1/guides/page` | GET | Full raw content of a guide/quest page (`id`) |
| `/api/v1/introspect/class` | GET | Runtime class reflection (`name=fqcn`) |
| `/api/v1/metrics` | GET | Server MSPT/TPS snapshot (`MinecraftServer.getTickTimesNanos()`) and per-endpoint latency/main-thread/cache stats |

All HTTP responses include `X-McInsight-MainThread-Us`, `X-McInsight-Total-Us`, and `X-McInsight-Cache` (`hit`/`miss`) diagnostic headers.

### 3.1 Threading & Caching Architecture

- **Static & Reload-Scoped Data (0ms Warm Main-Thread Overhead)**: `RecipeCollector`, `RegistryCollector`, `WorldgenAndLootCollector`, `TradeCollector`, `ResourceGuideCollector`, `StatusCollector`, and `ClassIntrospector` perform indexing, Codec encoding, file I/O, and filtering off-thread using generation-stamped caches (`InsightCacheManager`) invalidated automatically on datapack/resource reloads and world lifecycle transitions.
- **Live World & Player Data (Two-Phase Collection)**: `WorldTargetCollector`, `PlayerStateCollector`, and `AdvancementCollector` acquire minimal raw snapshots on the owning game thread, then run sorting, NBT conversion, tag lookup, and Gson serialization off-thread.

---

## 4. MCP Tools (11 Agent-Semantic Primitives)

| Tool | Semantic Purpose |
|---|---|
| `mc_status` | Connection health, active world state, JEI/EMI/REI/remote-sync/guide integrations, loaded mod list (`filteredModCount`) |
| `mc_player_state` | Live player state, inventory, open screen, and current environment (`biomeBaseTemperature`, `canSeeSky`, light, precipitation) |
| `mc_observe_world` | Raycast crosshair target (`looking_at`, optional `includeFluids`) or nearby area scan (`area`) |
| `mc_search_registry` | Resolve item/block/fluid/entity/tag IDs by ID or localized display name |
| `mc_inspect` | Inspect item/block/fluid/entity metadata (`blockInfo`, `fluidInfo`, `entityInfo`, `viewerInformation`) or paginated tag members |
| `mc_query_item_flow` | Cross-query how to obtain or use an item across recipes (including catalysts), loot + GLMs, directional trades, data maps, and `viewer_info` with per-source error reporting |
| `mc_query_recipes` | List recipe types / JEI+EMI+REI categories / built-in mod parsers (`listTypes=true`) or query recipes (`source`, `includeRaw`) |
| `mc_query_world_data` | Query worldgen (with biome `minTemp`/`maxTemp`), loot tables, paginated trades, and NeoForge data maps (`includeRaw`) |
| `mc_query_advancements` | Query advancement definitions, triggers, rewards, and paginated live progress |
| `mc_guides` | Search, list, or read Patchouli / GuideME / FTB Quests / Ponder / Oracle Index / lang entries (auto-following client language + `en_us` fallback) |
| `mc_inspect_code` | Bytecode ID-to-field-to-reference tracing (`trace_id`), JAR/Jar-in-Jar/pack script search (`search`), resource reading (`read_resource`), Vineflower/`javap` decompilation (`decompile`), or JVM class reflection (`reflect`) |

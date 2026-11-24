# McInsightMCP Architecture & Protocol Specification

## 1. Design Principle: Stateless Read-Only Introspection + 2-Tier Mechanical Parsers

```
Minecraft JVM (NeoForge / Fabric 1.21.1)
  ├── McInsight Mod (Read-Only, 127.0.0.1:25580..25599)
  │     ├── Native Minecraft Systems (RecipeManager CODEC, Dynamic Worldgen, LootTables, Registries/Tags)
  │     ├── Optional JEI Bridge (IJeiRuntime -> categories, catalysts, typed ingredients, IngredientInfo)
  │     ├── ResourceManager + FTB Quests SNBT Scanner (Patchouli / GuideME / Ponder / Oracle / Lang)
  │     ├── Player / Environment / Raycast / Area Scanner
  │     └── Runtime Class Reflection Introspector
  ▼ HTTP JSON API (Loopback only)
TypeScript MCP Server (Node 24, @modelcontextprotocol/server v2.4, Spec 2026-07-28)
  ├── Stateless Streamable HTTP (/mcp) & stdio (11 agent-semantic tools)
  ├── McHttpClient (parallel port auto-discovery 25580..25599)
  ├── Tier 1: Native Vanilla Parsers (vanilla recipes, worldgen ore/height/biome, loot pools/GLMs)
  ├── Tier 2: Version-Scoped Built-in Major Mod Parsers (Create 6.x, Mekanism 10.7.x, AE2 19.x, Oritech 1.x, Farmer's Delight 1.x, Ars Nouveau 5.x, TofuCraft 12.x)
  └── On-Demand JAR Inspector, Bytecode ID Tracer & Decompiler (adm-zip, pack script search, <clinit> field tracer, javap, Vineflower 1.12.0)
```

Mechanical parsing covers Minecraft's native systems (Tier 1) and explicitly version-gated major mod parsers (Tier 2). When a built-in mod parser's `modVersionRange` does not match the running mod version, it refuses to guess (`parserVersionMismatch`) and passes verbatim raw evidence to the AI. Parsed responses omit `rawJson` by default (`includeRaw=false`) and include it automatically whenever mechanical parsing is unavailable or `includeRaw=true`.

---

## 2. Mod Local HTTP API (`/api/v1/...`)

All endpoints bind strictly to `127.0.0.1` using `com.sun.net.httpserver.HttpServer`. Every response is JSON. Errors return `{ "ok": false, "code": "...", "error": "..." }`.

| Endpoint | Method | Description |
|---|---|---|
| `/api/v1/status` | GET | Mod version, MC/loader info, world state, active integrations, optional `includeMods=true` |
| `/api/v1/mods` | GET | Loaded mod list (`id`, `name`, `version`, `description`, `jarPath`) |
| `/api/v1/player` | GET | Player position, biome, live `environment` (`biomeBaseTemperature`, `canSeeSky`, `skyLight`, `blockLight`, `precipitation`), vitals, inventory, open screen |
| `/api/v1/looking-at` | GET | Raycast block/entity target (`maxDistance`, default `16`) |
| `/api/v1/scan` | GET | Nearby blocks, block entities, and entities (`radius`, `filter`) |
| `/api/v1/registry/search` | GET | Search registries (`kind=item\|block\|fluid\|entity\|tag\|item_tag\|block_tag\|fluid_tag\|entity_tag`, `q`, `mod`, `limit`, `offset`) |
| `/api/v1/registry/item` | GET | Detailed metadata for `id=namespace:path` (includes `blockInfo.isRandomlyTicking`, `stateProperties`, `destroySpeed`, and optional `jeiInformation`) |
| `/api/v1/registry/tag` | GET | List members of an `item`/`block`/`fluid`/`entity` tag (`kind`, `id`, `limit`) |
| `/api/v1/recipes/types` | GET | All `RecipeType` IDs + counts and JEI category UIDs + catalysts |
| `/api/v1/recipes/query` | GET | Unified `RecipeManager` raw JSON + JEI viewer extraction (`item`, `role`, `type`, `mod`, `source`, `limit`, `offset`) |
| `/api/v1/worldgen/summary` | GET | Dynamic registry counts for dimensions, biomes, structures, placed/configured features, and biome modifiers |
| `/api/v1/worldgen/query` | GET | Worldgen & biome modifier definitions (`kind`, `q`, `mod`, `searchContent`, `minTemp`, `maxTemp`, `summaryOnly`, `limit`, `offset`) |
| `/api/v1/loot/query` | GET | `LootTable`s & NeoForge GLMs with exact-ID/tag reverse `item` lookup (`q`, `item`, `mod`, `includeModifiers`, `limit`, `offset`) |
| `/api/v1/datamaps/query` | GET | NeoForge `data_maps` JSON definitions & filtered values (`q`, `registry`, `mod`, `limit`, `offset`) |
| `/api/v1/trades/query` | GET | Registered `VillagerTrades` & `WanderingTrader` offers (`item`, `profession`, `mod`, `limit`) |
| `/api/v1/advancements/query` | GET | Advancement definitions, rewards, and live player progress (`q`, `mod`, `status`, `includeRecipes`, `limit`) |
| `/api/v1/guides/list` | GET | Discovered guide books (`assets/` + `data/`), quests, and lang docs (`source`, `mod`) |
| `/api/v1/guides/search` | GET | Case-insensitive search across guide/quest/ponder/oracle/lang entries (`q`, `source`, `lang`, `includeAllLang`, `limit`) |
| `/api/v1/guides/page` | GET | Full raw content of a guide/quest page (`id`) |
| `/api/v1/introspect/class` | GET | Runtime class reflection (`name=fqcn`) |
| `/api/v1/metrics` | GET | Server MSPT/TPS snapshot (`MinecraftServer.getTickTimesNanos()`) and per-endpoint latency/main-thread/cache stats |

All HTTP responses include `X-McInsight-MainThread-Us`, `X-McInsight-Total-Us`, and `X-McInsight-Cache` (`hit`/`miss`) diagnostic headers.

### 2.1 Threading & Caching Architecture
- **Static & Reload-Scoped Data (0ms Warm Main-Thread Overhead)**: `RecipeCollector`, `RegistryCollector`, `WorldgenAndLootCollector`, `TradeCollector`, `ResourceGuideCollector`, `StatusCollector`, and `ClassIntrospector` perform indexing, Codec encoding, file I/O, and filtering on HTTP worker threads using generation-stamped caches (`InsightCacheManager`) invalidated automatically on datapack/resource reloads and world lifecycle transitions.
- **Live World & Player Data (Two-Phase Collection)**: `WorldTargetCollector`, `PlayerStateCollector`, and `AdvancementCollector` acquire minimal raw snapshots (`LevelChunkSection` direct reads skipping `hasOnlyAir()` sections, chunk-level `getBlockEntities()`, copied `ItemStack`s, biome climate/light samples, advancement progress snapshots) on the owning game thread, then run sorting, NBT conversion, tag lookup, and Gson serialization off-thread.

---

## 3. MCP Tools (11 Agent-Semantic Primitives)

| Tool | Semantic Purpose |
|---|---|
| `mc_status` | Connection health, active world state, integrations, loaded mod list |
| `mc_player_state` | Live player state, inventory, open screen, and current environment (`biomeBaseTemperature`, `canSeeSky`, light, precipitation) |
| `mc_observe_world` | Raycast crosshair target (`looking_at`) or nearby area scan (`area`) |
| `mc_search_registry` | Resolve item/block/fluid/entity/tag IDs by ID or localized display name |
| `mc_inspect` | Inspect item/block metadata (`isRandomlyTicking`, `stateProperties`, `jeiInformation`) or tag members |
| `mc_query_item_flow` | Cross-query how to obtain or use an item (recipes, loot + GLMs, trades, data maps, JEI info) |
| `mc_query_recipes` | List recipe types / built-in mod parsers (`listTypes=true`) or query recipes (`includeRaw` supported) |
| `mc_query_world_data` | Query worldgen (with biome `minTemp`/`maxTemp`), loot tables, trades, and data maps (`includeRaw` + pagination) |
| `mc_query_advancements` | Query advancement definitions, rewards, and live progress |
| `mc_guides` | Search, list, or read Patchouli / GuideME / FTB Quests / Ponder / Oracle Index / lang entries |
| `mc_inspect_code` | Bytecode ID-to-field-to-reference tracing (`trace_id`), JAR/pack script search (`search`), resource reading (`read_resource`), Vineflower/`javap` decompilation (`decompile`), or JVM class reflection (`reflect`) |

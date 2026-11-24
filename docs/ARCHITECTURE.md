# McInsightMCP Architecture & Protocol Specification

## 1. Design Principle: 3-Tier Hybrid Interpretation (Native + Versioned Mod Parsers + AI-First)

```
Minecraft JVM (NeoForge / Fabric 1.21.1)
  ├── McInsight Mod (Read-Only, 127.0.0.1:25580..25599)
  │     ├── Native Minecraft Systems (RecipeManager CODEC, Dynamic Worldgen, LootTables, Registries/Tags)
  │     ├── Optional JEI Bridge (IJeiRuntime -> categories, catalysts, typed ingredients, POJO snapshots)
  │     ├── ResourceManager + FTB Quests SNBT Scanner (Patchouli / GuideME / Ponder / Lang)
  │     ├── Player / Raycast / Area Scanner
  │     └── Runtime Class Reflection Introspector
  ▼ HTTP JSON API (Loopback only)
TypeScript MCP Server (Node 24, @modelcontextprotocol/server v2.4, Spec 2026-07-28)
  ├── Stateless Streamable HTTP (/mcp) & stdio
  ├── McHttpClient (port auto-discovery 25580..25599)
  ├── Tier 1: Native Vanilla Parsers (vanilla recipes, worldgen ore/height/biome, loot pools/conditions)
  ├── Tier 2: Version-Scoped Built-in Major Mod Parsers (Create 6.x, Mekanism 10.7.x, AE2 19.x, Oritech 1.x, Farmer's Delight 1.x, Ars Nouveau 5.x, TofuCraft 12.x)
  ├── Tier 3: On-Demand Mod JAR Inspector & Decompiler (adm-zip, pack script search, javap, Vineflower 1.12.0)
  └── Schema-Free Knowledge Store (highest override priority for custom extractors)
        ├── Freeform Markdown Notes (knowledge/notes/*.md with minimal version/ID frontmatter)
        └── Optional Sandboxed VM Extractors (knowledge/extractors/*.json, 100ms timeout, no Node globals)
```

Mechanical parsing covers Minecraft's native systems (Tier 1) and explicitly version-gated major mod parsers (Tier 2). When a built-in mod parser's `modVersionRange` does not match the running mod version, it refuses to guess (`parserVersionMismatch`) and passes verbatim raw evidence to the AI. Arbitrary mod mechanics and undocumented systems are handled via Tier 3 + AI reasoning without rigid intermediate DSLs.

---

## 2. Mod Local HTTP API (`/api/v1/...`)

All endpoints bind strictly to `127.0.0.1` using `com.sun.net.httpserver.HttpServer`. Every response is JSON. Errors return `{ "ok": false, "code": "...", "error": "..." }`.

| Endpoint | Method | Description |
|---|---|---|
| `/api/v1/status` | GET | Mod version, MC/loader info, world state, active integrations, optional `includeMods=true` |
| `/api/v1/mods` | GET | Loaded mod list (`id`, `name`, `version`, `description`, `jarPath`) |
| `/api/v1/player` | GET | Player position, vitals, effects, inventory, open screen & container slots |
| `/api/v1/looking-at` | GET | Raycast block/entity target (`maxDistance`, default `16`) |
| `/api/v1/scan` | GET | Nearby blocks, block entities, and entities (`radius`, `filter`) |
| `/api/v1/registry/search` | GET | Search registries (`kind=item\|block\|fluid\|entity\|tag`, `q`, `mod`, `limit`, `offset`) |
| `/api/v1/registry/item` | GET | Detailed metadata for `id=namespace:path` |
| `/api/v1/recipes/types` | GET | All `RecipeType` IDs + counts and JEI category UIDs + catalysts |
| `/api/v1/recipes/query` | GET | Unified `RecipeManager` raw JSON + JEI viewer extraction (`item`, `role`, `type`, `mod`, `source`, `limit`, `offset`) |
| `/api/v1/worldgen/summary` | GET | Dynamic registry counts for dimensions, biomes, structures, placed/configured features |
| `/api/v1/worldgen/query` | GET | Raw JSON for `placed_feature`, `configured_feature`, `biome`, `structure`, `dimension_type` |
| `/api/v1/loot/query` | GET | Raw JSON for `LootTable`s (server reloadable registries or `ResourceManager` fallback) |
| `/api/v1/guides/list` | GET | Discovered guide books, quests, and lang docs (`source`, `mod`) |
| `/api/v1/guides/search` | GET | Case-insensitive search across guide/quest/ponder/lang entries (`q`, `lang`, `limit`) |
| `/api/v1/guides/page` | GET | Full raw content of a guide/quest page (`id`) |
| `/api/v1/introspect/class` | GET | Runtime class reflection (`name=fqcn`) |

---

## 3. Knowledge Store Format

### 3.1 Freeform Markdown Notes (`knowledge/notes/<id>.md`)
Minimal YAML frontmatter (`id`, `title`, `modId`, `modVersionRange`, `relatedIds`, `sourceClasses`, `updatedAt`) + unrestricted Markdown body explaining multiblocks, in-world behaviors, custom tick rules, or progression tips. Automatically surfaced by `mc_query_recipes`, `mc_inspect_item`, and `mc_looking_at` when IDs match and `modVersionRange` satisfies the active mod version.

### 3.2 Optional Sandboxed JS Extractor (`knowledge/extractors/<id>.json`)
Runs inside `node:vm` (`Object.create(null)`, `codeGeneration: { strings: false, wasm: false }`, `timeout: 100ms`, zero Node globals). Defines `function extract(raw, context) { return { ... }; }` returning any plain JSON object. Validated via dry-run before persistence.

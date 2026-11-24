# McInsightMCP

**English** | [日本語](README.ja.md)

A generic, read-only, stateless **Minecraft 1.21.1** (NeoForge + Fabric) runtime introspection mod paired with an **MCP `2026-07-28`** compliant TypeScript server (stateless Streamable HTTP & stdio) organized around agent-semantic observation tools.

---

## Design Philosophy

Modded Minecraft packs combine hundreds of mods, KubeJS overrides, datapacks, multiblock structures, and custom in-world mechanics that cannot be captured by a single rigid recipe schema. McInsightMCP separates runtime observation into two mechanical parser tiers plus direct primary-evidence access:

1. **Tier 1 — Native Minecraft Parsers (`src/parsers/nativeParsers.ts`)**:
   Mechanically normalizes Minecraft's built-in data formats: vanilla crafting/smelting/stonecutting/smithing recipes, dynamic worldgen (`configured_feature` ore veins, `placed_feature` height/count rules, biome climate/spawners, structures, biome modifiers), loot tables & Global Loot Modifiers, and registries/tags.
2. **Tier 2 — Version-Scoped Major Mod Parsers (`src/parsers/modParsers/`)**:
   Built-in parsers for major 1.21.1 mods (`create` 6.x, `mekanism` 10.7.x, `ae2` 19.x, `oritech` 1.x, `farmersdelight` 1.x, `ars_nouveau` 5.x, `tofucraft` 12.x), strictly gated by semver `modVersionRange` against live loaded mod versions. When a mod version falls outside the tested range, the parser refuses to guess (`parserVersionMismatch`) and passes verbatim raw data to the AI.
3. **Primary Evidence & Bytecode Introspection**:
   For any other mod, multiblock, or undocumented in-world mechanic, the server exposes `Recipe.CODEC` raw JSON (with KubeJS/datapack changes applied), optional JEI categories/catalysts/info descriptions, BlockEntity NBT, loaded guides/quests (Patchouli, GuideME, FTB Quests, Create Ponder, Oracle Index, lang files), runtime class reflection, and on-demand JAR inspection, deterministic ID-to-field bytecode tracing, and **Vineflower 1.12.0** / `javap` Java decompilation.

---

## Key Features

- **Zero Hard Mod Dependencies (Multiloader MC 1.21.1)**:
  Runs standalone on NeoForge or Fabric with any modpack. Optional integrations (JEI runtime, Patchouli, GuideME, FTB Quests, Create Ponder) attach automatically when present.
- **Pure Read-Only & Stateless**:
  Never modifies the game world or maintains persistent server-side state. Works on multiplayer servers even when McInsight is only installed on the client, and exposes authoritative BlockEntity NBT and dynamic loot tables in singleplayer or when installed server-side.
- **No Native Window / GLFW Dependencies**:
  Exposes structured data over a pure JDK loopback HTTP API (`127.0.0.1:25580..25599`) — never touches GLFW or screen capture hooks.
- **MCP `2026-07-28` Compliant**:
  Built on `@modelcontextprotocol/server` v2.4 and `@modelcontextprotocol/node` v2.1 with stateless Streamable HTTP (`/mcp`, `server/discover`, Host/Origin localhost guards) and `stdio`.

---

## Repository Structure

```
McInsightMCP/
├── docs/
│   └── ARCHITECTURE.md     # HTTP API contract & parser/tool design
├── mod/                    # MC 1.21.1 Multiloader Gradle project (common / neoforge / fabric)
└── mcp-server/             # TypeScript MCP server (2026-07-28)
```

For full HTTP endpoint contracts and MCP tool details, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

---

## Build & Run

### 1. Build the Minecraft Mod (JDK 21)

```bash
cd mod
./gradlew :neoforge:build :fabric:build
```

Generated artifacts:
- `mod/neoforge/build/libs/mcinsight-neoforge-1.21.1-1.0.0.jar`
- `mod/fabric/build/libs/mcinsight-fabric-1.21.1-1.0.0.jar`

Drop the appropriate JAR into your Minecraft `mods/` folder. No extra dependencies are required (JEI is optional).

### 2. Build, Test, and Run the MCP Server (Node.js >= 20, pnpm)

```bash
cd mcp-server
pnpm install
pnpm typecheck
pnpm build
pnpm test
```

Start in **Stateless Streamable HTTP** mode (`http://127.0.0.1:3876/mcp`):

```bash
node dist/index.js --http --port 3876
```

Start in **stdio** mode:

```bash
node dist/index.js --stdio
```

Optional CLI flags / environment variables:
- `--mc-url <url>` (`MCINSIGHT_URL`): Explicit mod API URL (auto-discovers `127.0.0.1` / `[::1]` ports `25580..25599` by default)
- `--mods-dir <path>` (`MCINSIGHT_MODS_DIR`): Mods directory for offline JAR inspection/decompilation
- `--game-dir <path>` (`MCINSIGHT_GAME_DIR`): Game directory for offline KubeJS/FTB Quests scanning

---

## MCP Tools Overview (11 Semantic Tools)

- **World & Player State**:
  - `mc_status`: Runtime status, active world state, integrations, and loaded mod list
  - `mc_player_state`: Position, biome, live environment (`biomeBaseTemperature`, `canSeeSky`, `skyLight`, `blockLight`, `precipitation`), vitals, inventory, and open screen slots
  - `mc_observe_world`: Raycast crosshair target (`looking_at`) or scan surrounding area (`area`)
- **Registry & Metadata**:
  - `mc_search_registry`: Search items, blocks, fluids, entities, and tags
  - `mc_inspect`: Inspect item/block metadata (`isRandomlyTicking`, `stateProperties`, `destroySpeed`, `jeiInformation`) or tag members
- **Game Mechanics & Systems**:
  - `mc_query_item_flow`: Cross-query how to obtain or use an item across recipes, loot tables + GLMs, trades, data maps, and JEI info
  - `mc_query_recipes`: List recipe types / built-in mod parsers or query recipes by type, mod, or item (`includeRaw` supported)
  - `mc_query_world_data`: Query worldgen (with biome temperature filters and `includeRaw`), loot tables, villager trades, and NeoForge data maps
  - `mc_query_advancements`: Query advancement definitions, rewards, and live player progress
- **Documentation & Code**:
  - `mc_guides`: Search, list, and read Patchouli, GuideME, FTB Quests, Create Ponder, Oracle Index, and lang docs
  - `mc_inspect_code`: Trace ID registrations & field references in JVM bytecode (`trace_id`), grep JARs/pack scripts (`search`), read JAR resources (`read_resource`), decompile Java classes via Vineflower/`javap` (`decompile`), or reflect live JVM classes (`reflect`)

---

## License

[MIT](LICENSE)

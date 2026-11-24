# McInsightMCP

**English** | [日本語](README.ja.md)

A generic, read-only **Minecraft 1.21.1** (NeoForge + Fabric) runtime introspection mod paired with an **MCP `2026-07-28`** compliant TypeScript server (stateless Streamable HTTP & stdio) designed around an **AI-first, self-extending game knowledge architecture**.

---

## Design Philosophy

Modded Minecraft packs combine hundreds of mods, KubeJS overrides, datapacks, multiblock structures, and custom in-world mechanics that cannot be captured by a single rigid recipe schema. McInsightMCP splits responsibility into three tiers:

1. **Tier 1 — Native Minecraft Parsers (`src/parsers/nativeParsers.ts`)**:
   Mechanically normalizes Minecraft's built-in data formats: vanilla crafting/smelting/stonecutting/smithing recipes, dynamic worldgen (`configured_feature` ore veins, `placed_feature` height/count rules, biomes, structures), loot tables (pools, rolls, silk-touch/fortune conditions), and registries/tags.
2. **Tier 2 — Version-Scoped Major Mod Parsers (`src/parsers/modParsers/`)**:
   Built-in parsers for major 1.21.1 mods (`create` 6.x, `mekanism` 10.7.x, `ae2` 19.x, `oritech` 1.x, `farmersdelight` 1.x, `ars_nouveau` 5.x, `tofucraft` 12.x), strictly gated by semver `modVersionRange` against live loaded mod versions. When a mod version falls outside the tested range, the parser refuses to guess (`parserVersionMismatch`) and passes verbatim raw data to the AI.
3. **Tier 3 — AI Reasoning + Schema-Free Knowledge Store**:
   For any other mod, multiblock, or undocumented mechanic, the server exposes primary runtime evidence: `Recipe.CODEC` raw JSON (with KubeJS/datapack changes applied), optional JEI categories/catalysts/POJO snapshots, BlockEntity NBT, loaded guides/quests (Patchouli, GuideME, FTB Quests, Create Ponder, lang files), runtime class reflection, and on-demand JAR inspection / **Vineflower 1.12.0** & `javap` Java decompilation. Once the AI analyzes a system, it can persist freeform Markdown notes (`knowledge/notes/*.md`) and optional sandboxed JS extractors (`knowledge/extractors/*.json`) for future queries.

---

## Key Features

- **Zero Hard Mod Dependencies (Multiloader MC 1.21.1)**:
  Runs standalone on NeoForge or Fabric with any modpack. Optional integrations (JEI runtime, Patchouli, GuideME, FTB Quests, Create Ponder) attach automatically when present.
- **Read-Only & Client-First**:
  Works on multiplayer servers even when McInsight is only installed on the client (vanilla syncs recipes and registries to clients). Automatically exposes richer server-side data (authoritative BlockEntity NBT, dynamic loot tables) in singleplayer or when installed server-side.
- **No Native Window / GLFW Dependencies**:
  Exposes structured data over a pure JDK loopback HTTP API (`127.0.0.1:25580..25599`) — never touches GLFW or screen capture hooks.
- **MCP `2026-07-28` Compliant**:
  Built on `@modelcontextprotocol/server` v2.4 and `@modelcontextprotocol/node` v2.1 with stateless Streamable HTTP (`/mcp`, `server/discover`, Host/Origin localhost guards) and `stdio`.
- **Sandboxed Self-Extension**:
  Optional custom JS extractors run inside a strict `node:vm` context (`Object.create(null)`, no `process`/`require`/`fs`, 100ms timeout) and must pass a dry-run before persistence.

---

## Repository Structure

```
McInsightMCP/
├── docs/
│   └── ARCHITECTURE.md     # HTTP API contract & 3-tier knowledge design
├── mod/                    # MC 1.21.1 Multiloader Gradle project (common / neoforge / fabric)
└── mcp-server/             # TypeScript MCP server (2026-07-28) + knowledge store
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
- `--knowledge-dir <path>` (`MCINSIGHT_KNOWLEDGE_DIR`): Custom knowledge directory (defaults to `mcp-server/knowledge`)

---

## MCP Tools Overview

- **Game State**: `mc_status`, `mc_player_state`, `mc_looking_at`, `mc_scan_area`, `mc_search_registry`, `mc_inspect_item`
- **Recipes, Worldgen & Loot**: `mc_list_recipe_types`, `mc_query_recipes`, `mc_query_worldgen`, `mc_query_loot`
- **Guides & Quests**: `mc_search_guides`, `mc_get_guide_page`
- **Introspection & Decompilation**: `mc_inspect_class`, `mc_search_jars`, `mc_read_jar_resource`, `mc_decompile_class`
- **Knowledge & Extractors**: `mc_save_knowledge_note`, `mc_search_knowledge`, `mc_get_knowledge_note`, `mc_register_extractor`, `mc_test_extractor`, `mc_list_knowledge`

---

## License

[MIT](LICENSE)

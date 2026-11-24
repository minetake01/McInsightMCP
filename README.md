# McInsightMCP

**English** | [日本語](README.ja.md)

[![CI](https://github.com/minetake01/McInsightMCP/actions/workflows/ci.yml/badge.svg)](https://github.com/minetake01/McInsightMCP/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/minetake01/McInsightMCP?include_prereleases&label=release)](https://github.com/minetake01/McInsightMCP/releases)
[![Status: Beta](https://img.shields.io/badge/Status-Beta-yellow.svg)](CHANGELOG.md)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-NeoForge%20%7C%20Fabric-62B47A.svg)](https://github.com/minetake01/McInsightMCP/releases)

**Ask your AI assistant about the Minecraft world you are actually playing.**

McInsightMCP connects MCP-compatible AI assistants (Claude Desktop, Cursor, Claude Code, VS Code, and others) directly to your running **Minecraft** game. Instead of guessing from outdated wikis, your AI can look up real recipes in your modpack (including KubeJS and datapack tweaks), see what block or machine you are looking at, check your inventory, read in-game guidebooks and quests, and inspect how unfamiliar mods actually work.

> [!NOTE]
> **Beta Release (`0.1.0-beta.2`)**: McInsightMCP is currently in beta. MCP tool schemas, response formats, and configuration options may change before `1.0.0`. Bug reports and feedback are welcome via [GitHub Issues](https://github.com/minetake01/McInsightMCP/issues).

---

## Why Use McInsightMCP?

- **Accurate to Your Modpack**: Reads live recipes, ore generation, loot tables, villager trades, advancements, and KubeJS/datapack overrides straight from the running game.
- **100% Read-Only & Safe**: Never breaks blocks, edits your world, moves your player, or sends commands. It only observes.
- **Local-Only by Default**: Runs on `127.0.0.1` on your machine — no external servers, no telemetry, and no screen capture.
- **Client-Friendly Multiplayer**: Works on multiplayer servers even when installed only on your client (recipes, inventory, raycast target, and guides). In singleplayer — or when also installed on the server — it additionally reads full machine/chest NBT and dynamic loot tables.
- **Works With Any Modpack**: Automatically integrates with optional mods when installed, and can even inspect mod resources or decompile mod classes when documentation is missing.

---

## What You Can Ask Your AI

- *"How do I craft or obtain `mekanism:atomic_alloy` in this pack, and do I already have the materials in my inventory?"*
- *"What is this machine I'm looking at right now, and why isn't it running?"*
- *"At what Y-level and in which biomes does this modpack's osmium or platinum ore generate?"*
- *"Search the Patchouli / FTB Quests / Create Ponder / GuideME docs for how to automate this resource."*
- *"I can't find any wiki page for this modded item — check the mod itself and explain how the mechanic works."*

---

## Requirements & Supported Integrations

| Component | Requirement |
|---|---|
| **Minecraft** | Currently `1.21.1` (Java 21) — see [Releases](https://github.com/minetake01/McInsightMCP/releases) for supported versions |
| **NeoForge** | Standalone — no required mod dependencies |
| **Fabric** | Requires [Fabric API](https://modrinth.com/mod/fabric-api) |
| **MCP Server** | [Node.js](https://nodejs.org/) `>= 20` |

### Optional In-Game Integrations (Auto-Detected)
- **JEI, EMI, and REI** (recipe categories, catalysts, and item/fluid/chemical info pages)
- **In-Game Guides & Quests**: Patchouli, GuideME, FTB Quests, Create Ponder, Oracle Index, and mod language files

### Built-In Mod Recipe Formatting
Works with **any** modpack via raw recipe data, and includes dedicated structured recipe formatting for **Create**, **Mekanism**, **Applied Energistics 2**, **Oritech**, **Farmer's Delight**, **Ars Nouveau**, and **TofuCraftReload**.

---

## Quick Start

### Step 1: Install the Minecraft Mod

1. Download the mod JAR for your Minecraft version and loader from [GitHub Releases](https://github.com/minetake01/McInsightMCP/releases):
   - **NeoForge**: `mcinsight-neoforge-<mc_version>-<version>.jar`
   - **Fabric**: `mcinsight-fabric-<mc_version>-<version>.jar` (also make sure **Fabric API** is in `mods/`)
2. Place the JAR into your Minecraft instance's `mods/` folder.
3. Launch Minecraft and load into a world.

### Step 2: Connect Your AI Client

#### Option A: `stdio` (Recommended)

Recommended for Claude Desktop, Cursor, Claude Code, and VS Code. Add this to your MCP client configuration:

```json
{
  "mcpServers": {
    "mcinsight": {
      "command": "npx",
      "args": ["-y", "mcinsight-mcp-server@beta", "--stdio"]
    }
  }
}
```

With `pnpm`, you can also use `"command": "pnpx"` and `"args": ["mcinsight-mcp-server@beta", "--stdio"]`.

#### Option B: Streamable HTTP

Start the server locally:

```bash
npx -y mcinsight-mcp-server@beta --http --port 3876
# or with pnpm:
pnpx mcinsight-mcp-server@beta --http --port 3876
```

Then point your MCP client to `http://127.0.0.1:3876/mcp`.

#### Manual / Offline Setup (GitHub Releases)

You can also download and extract `mcinsight-mcp-server-<version>` from [GitHub Releases](https://github.com/minetake01/McInsightMCP/releases), install runtime dependencies inside the extracted folder (`pnpm install --prod` or `npm install --omit=dev`), and run `node /path/to/mcinsight-mcp-server/dist/index.js --stdio` (or build from source — see below).

> **Tip**: By default, the MCP server automatically discovers a running McInsight mod on `127.0.0.1` ports `25580..25599` — no manual port configuration needed.

---

## Optional Configuration

### Minecraft Mod (`config/mcinsight.json`)

Generated automatically on first launch:

| Key | Default | Description |
|---|---|---|
| `enabled` | `true` | Enable or disable the local HTTP API |
| `port` | `25580` | Starting loopback port to bind (`1024..65535`) |
| `portRangeSize` | `20` | Number of consecutive ports to try if the port is busy (`1..50`) |
| `authToken` | `""` | Optional Bearer token required for API requests |
| `maxScanRadius` | `16` | Maximum radius for nearby area scans (`1..32`) |
| `maxRecipeResults` | `100` | Maximum recipes returned per query (`1..500`) |

### MCP Server CLI Flags & Environment Variables

| CLI Flag | Environment Variable | Purpose |
|---|---|---|
| `--stdio` | — | Run over stdio (default) |
| `--http` | — | Run as a local Streamable HTTP server |
| `--port <port>` | `MCP_PORT` | HTTP server port (default `3876`) |
| `--mc-url <url>` | `MCINSIGHT_URL` | Explicit mod URL (skips port auto-discovery) |
| `--token <token>` | `MCINSIGHT_TOKEN` | Bearer token matching `authToken` in `mcinsight.json` |
| `--mods-dir <path>` | `MCINSIGHT_MODS_DIR` | Mods folder path for offline JAR inspection |
| `--game-dir <path>` | `MCINSIGHT_GAME_DIR` | Game folder path for offline KubeJS / FTB Quests scanning |
| `--tools-dir <path>` | `MCINSIGHT_TOOLS_DIR` | Cache directory for downloaded decompiler tools (Vineflower) |

---

## Available AI Tools

McInsightMCP exposes 11 tools to your AI assistant:

- **World & Player**: `mc_status`, `mc_player_state`, `mc_observe_world` (what you are looking at — blocks, fluids, entities — or nearby blocks/entities)
- **Items & Blocks**: `mc_search_registry`, `mc_inspect` (item/block/fluid/entity properties, tags, JEI/EMI/REI viewer info)
- **Progression & Crafting**: `mc_query_item_flow`, `mc_query_recipes`, `mc_query_world_data` (worldgen, loot tables, trades, NeoForge data maps), `mc_query_advancements`
- **Guides & Mod Internals**: `mc_guides` (Patchouli, GuideME, FTB Quests, Ponder, Oracle Index, lang), `mc_inspect_code` (resource/script search, Jar-in-Jar, & Java class inspection)

---

## Building from Source

Requires **JDK 21** for the mod and **Node.js >= 20** + **pnpm** for the MCP server.

```bash
# 1. Test common logic & build the NeoForge and Fabric mod JARs
cd mod
./gradlew :common:test :neoforge:build :fabric:build
# Output JARs:
#   mod/neoforge/build/libs/mcinsight-neoforge-<mc_version>-0.1.0-beta.2.jar
#   mod/fabric/build/libs/mcinsight-fabric-<mc_version>-0.1.0-beta.2.jar

# 2. Build and test the MCP server
cd ../mcp-server
pnpm install
pnpm typecheck
pnpm build
pnpm test
```

---

## Documentation & Links

- [Architecture & Protocol Specification](docs/ARCHITECTURE.md) — Technical details, parser tiers, HTTP endpoints, and threading model
- [Contributing Guide](CONTRIBUTING.md) — Development setup, testing, and how to add mod parsers
- [Security Policy](SECURITY.md) — Read-only & loopback guarantees and vulnerability reporting
- [Changelog](CHANGELOG.md) — Release history
- [License (MIT)](LICENSE)

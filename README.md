# McInsightMCP

**English** | [日本語](README.ja.md)

**Ask your AI assistant about the Minecraft world you are actually playing.**

McInsightMCP connects MCP-compatible AI assistants (Claude Desktop, Cursor, Claude Code, VS Code, and others) directly to your running **Minecraft** game. Instead of guessing from outdated wikis, your AI can check real recipes in your modpack (including KubeJS and datapack tweaks), inspect the block or machine you are looking at, check your inventory, read in-game guidebooks and quests, and inspect mod files when documentation is missing.

> **Beta:** McInsightMCP is currently in beta, so configuration options and internal tool behavior may change before `1.0.0`. Bug reports and feedback are welcome on [GitHub Issues](https://github.com/minetake01/McInsightMCP/issues).

## What You Can Ask

- *"How do I craft `mekanism:atomic_alloy` in this modpack, and do I have the materials in my inventory?"*
- *"Show me the crafting recipe for `minecraft:oak_planks` as a picture."*
- *"What is this machine I'm looking at, and why isn't it running?"*
- *"At what Y-level and in which biomes does osmium or platinum ore generate in this pack?"*
- *"Search Patchouli, FTB Quests, Create Ponder, or GuideME for how to automate this resource."*
- *"There's no wiki page for this modded item—check the mod itself and explain how it works."*

## Features

- **Accurate to Your Modpack**: Reads live recipes, KubeJS and datapack overrides, ore generation, loot tables, villager trades, and advancements straight from your running game.
- **Recipe Diagrams**: Draws a recipe the way its in-game viewer draws it—the machine or crafting GUI, its slots, and its arrows—and returns the image, so the assistant can show the recipe instead of describing it (JEI on every supported version; EMI and REI on `1.21.1`).
- **Read-Only and Safe**: Never breaks or places blocks, edits your world, moves your player, or runs commands.
- **Local-Only and Private**: Communicates strictly over `127.0.0.1` on your machine—no external servers, no telemetry, and no screen capture. Recipe diagrams are drawn off-screen from the recipe layout, never cropped from your display.
- **Singleplayer and Multiplayer**: Works client-only on multiplayer servers for recipes, inventory, what you are looking at, and guides. In singleplayer—or when the server also has McInsight installed—it also reads server-side data such as ore/world generation, loot tables, villager trades, advancement progress, NeoForge data maps, and block-entity contents (chests and machines).
- **Works With Any Modpack**: Automatically integrates with supported recipe viewers and guide mods, and can inspect mod resources or decompile mod classes when docs are missing.

## Requirements & Mod Support

| Component | Requirement |
|---|---|
| **Minecraft** | `1.21.1` (Java 21), `26.1.2`, `26.3` (Java 25) |
| **NeoForge** | Standalone (no required dependencies) |
| **Fabric** | Requires [Fabric API](https://modrinth.com/mod/fabric-api) |
| **MCP Server** | [Node.js](https://nodejs.org/) `22.12+` |

### Optional Integrations (Auto-Detected)

- **Recipe Viewers**: JEI, REI, and EMI (`1.21.1` only)
- **Guides & Quests**: Patchouli, GuideME, FTB Quests, Create Ponder, Oracle Index, and mod language files
- **Enhanced Recipe Formatting**: Works with any modpack via raw recipe data, with dedicated structured formatting for Create, Mekanism, Applied Energistics 2, Oritech, Farmer's Delight, Ars Nouveau, and TofuCraftReload

## Setup

### 1. Install the Minecraft Mod

1. Download the mod JAR for your Minecraft version and loader (on Fabric, also install [Fabric API](https://modrinth.com/mod/fabric-api)).
2. Place it in your Minecraft instance's `mods/` folder.
3. Launch Minecraft and load into a world.

### 2. Connect Your AI Client

Add the following to your AI client's MCP configuration (recommended for Claude Desktop, Cursor, Claude Code, and VS Code):

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

The MCP server automatically finds the running mod on `127.0.0.1` ports `25580`–`25599`, so no port setup is needed in normal use.

If your client prefers Streamable HTTP instead of `stdio`, run the server locally and connect to `http://127.0.0.1:3876/mcp`:

```bash
npx -y mcinsight-mcp-server@beta --http --port 3876
```

Pre-packaged offline archives of the MCP server are also available on [GitHub Releases](https://github.com/minetake01/McInsightMCP/releases).

## Optional Configuration

Most users do not need to change any settings.

- **Minecraft Mod (`config/mcinsight.json`)**: Generated on first launch. Supports `enabled`, `port` (default `25580`), `authToken`, and scan, block-entity sync, and recipe result limits.
- **MCP Server Flags & Environment Variables**: Use `--mc-url` (`MCINSIGHT_URL`) for a custom mod port, `--token` (`MCINSIGHT_TOKEN`) when `authToken` is set, and `--mods-dir` / `--game-dir` (`MCINSIGHT_MODS_DIR` / `MCINSIGHT_GAME_DIR`) to inspect mod files offline without Minecraft running.

See [Architecture & Full Configuration](docs/ARCHITECTURE.md#10-configuration-and-limits) for all keys, limits, and flags.

## Links

- [Architecture & Configuration](docs/ARCHITECTURE.md)
- [Contributing & Building from Source](CONTRIBUTING.md)
- [Security Policy](SECURITY.md)
- [Changelog](CHANGELOG.md)
- [License (MIT)](LICENSE)

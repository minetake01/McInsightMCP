# Contributing to McInsightMCP

Thank you for your interest in contributing to McInsightMCP!

## Prerequisites

- **Minecraft Mod (`mod/`)**: JDK 21
- **MCP Server (`mcp-server/`)**: Node.js `>= 20` and `pnpm` (`11.x`)

## Development Setup & Verification

### 1. Minecraft Mod (`mod/`)

```bash
cd mod
./gradlew :neoforge:build :fabric:build
```

Generated mod JARs:
- `mod/neoforge/build/libs/mcinsight-neoforge-<mc_version>-<version>.jar`
- `mod/fabric/build/libs/mcinsight-fabric-<mc_version>-<version>.jar`

### 2. MCP Server (`mcp-server/`)

```bash
cd mcp-server
pnpm install --frozen-lockfile
pnpm typecheck
pnpm build
pnpm test
```

## Architecture & Conventions

Please read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before making structural changes.

- **Read-Only & Stateless**: Neither the mod nor the MCP server should mutate world state, move the player, execute commands, or hold persistent server-side state.
- **No Native Window / GLFW Dependencies**: Keep the mod pure JDK HTTP (`127.0.0.1`).
- **Two-Phase Threading**: Keep main-thread work minimal (`WorldTargetCollector`, `PlayerStateCollector`, `AdvancementCollector` snapshot minimal data and process off-thread; static/reload-scoped collectors run off-thread with generation-stamped caches).
- **Adding a Tier 2 Mod Parser**:
  1. Add a new parser under `mcp-server/src/parsers/modParsers/` implementing `BuiltinModParser` (`mcp-server/src/parsers/modParsers/types.ts`).
  2. Pin `modVersionRange` strictly using `semver` to the tested mod version range — never guess across untested major/minor versions.
  3. Register it in `mcp-server/src/parsers/modParsers/index.ts`.
  4. Add unit tests in `mcp-server/tests/modParsers.test.ts` covering both valid parsing and `parserVersionMismatch` behavior.

## Version Sync

When bumping the release version, keep all four version declarations synchronized:

1. `mod/gradle.properties` (`version`)
2. `mod/common/src/main/java/net/minetake/mcinsight/McInsightConstants.java` (`MOD_VERSION`)
3. `mcp-server/package.json` (`version`)
4. `mcp-server/src/server.ts` (`version` in `new McpServer(...)`)

## Pull Request Checklist

- [ ] `pnpm typecheck`, `pnpm build`, and `pnpm test` pass in `mcp-server/`
- [ ] `./gradlew :neoforge:build :fabric:build` passes in `mod/`
- [ ] Documentation (`README.md`, `README.ja.md`, `docs/ARCHITECTURE.md`, `CHANGELOG.md`) updated if user-facing behavior or tools changed
- [ ] Changes keep the mod 100% read-only and loopback-only

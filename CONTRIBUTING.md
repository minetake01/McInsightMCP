# Contributing to McInsightMCP

Thank you for your interest in contributing to McInsightMCP!

## Prerequisites

- **Minecraft Mod (`mod/`)**: JDK 21 for the mod. Gradle itself runs on JDK 25 (`mod/gradle/gradle-daemon-jvm.properties`); it uses an installed JDK 25 or downloads one.
- **MCP Server (`mcp-server/`)**: Node.js `>= 22.12` and the `pnpm` version pinned in `mcp-server/package.json` (`packageManager`; Corepack picks it up)

## Development Setup & Verification

### 1. Minecraft Mod (`mod/`)

Each Minecraft version listed in `mod/gradle.properties` (`minecraft_versions`) is a Gradle node,
`:common:<version>`, `:neoforge:<version>` and `:fabric:<version>`. Build the one you are working on:

```bash
cd mod
./gradlew :common:1.21.1:test :neoforge:1.21.1:build :fabric:1.21.1:build
```

The whole matrix is the same command for each version, and CI runs it before publishing:

```bash
./gradlew :common:1.21.1:test :common:26.1.2:test :common:26.3:test \
          :neoforge:1.21.1:build :neoforge:26.1.2:build :neoforge:26.3:build \
          :fabric:1.21.1:build :fabric:26.1.2:build :fabric:26.3:build
```

Generated mod JARs:
- `mod/neoforge/versions/<mc_version>/build/libs/mcinsight-neoforge-<mc_version>-<version>.jar`
- `mod/fabric/versions/<mc_version>/build/libs/mcinsight-fabric-<mc_version>-<version>.jar`

A rename between versions goes into `game/Versioned` or the Stonecutter replacements in
`mcinsight.node.gradle`; a difference in shape is a `//? if` at the call site; a difference in whole
files is a `files { disable(...) }` rule. Never branch the repository. See `docs/ARCHITECTURE.md` for
the layout.

### 2. MCP Server (`mcp-server/`)

```bash
cd mcp-server
pnpm install --frozen-lockfile
pnpm typecheck
pnpm build
pnpm test
```

### 3. End-to-End in Real Minecraft (`mcp-server/e2e/`)

The e2e runner builds the mod, starts real Minecraft clients and dedicated servers, and checks every
tool through MCP. A client opens a window; close nothing by hand, the runner stops what it started.

```bash
cd mcp-server
pnpm e2e                                  # quick: NeoForge singleplayer
pnpm e2e --preset release                 # every loader × singleplayer / multiplayer with and without the server mod
pnpm e2e --scenario singleplayer --suite recipes --skip-build   # one area while working
pnpm e2e --extra-mods /path/to/mods       # local only: try an arbitrary mod set
pnpm e2e --list                           # presets, scenarios, suites
```

Logs of every process are kept under `mod/<loader>/versions/<mc_version>/runs/e2e/<loader>-<scenario>/logs/`. See
[docs/ARCHITECTURE.md §12](docs/ARCHITECTURE.md) for how the runs are launched.

## Architecture & Conventions

Please read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before making structural changes.

- **Read-Only & Stateless**: Neither the mod nor the MCP server should mutate world state, move the player, execute commands, or hold persistent server-side state.
- **No Native Window / GLFW Dependencies**: Keep the mod pure JDK HTTP (`127.0.0.1`).
- **Add an endpoint through the contract, not around it**: declare it in `InsightEndpoint` (id, path, data/player scope, budget, allowed parameters, page bounds), bind it in `InsightHandlers`, and write the handler as `public static JsonObject handle(RequestContext ctx, Params p)`. Throw `new InsightException(InsightError.X, "...")` instead of returning an error object, page through `Page.of` / `Page.uncounted`, and report unavailable composite parts through `PartialData`. Parameters must be read only through `Params`, which is what makes the HTTP and packet transports accept the same values.
- **Mirror the contract on the TypeScript side**: a new or changed endpoint goes into `mcp-server/src/api/endpoints.ts` with its parameters and page bounds; `mcp-server/tests/contract.test.ts` reads the Java declarations and fails when the two drift. Do not build URLs or re-declare response shapes in a tool.
- **Keep provenance out of responses**: whether a value came from the client or the server is not something a caller should branch on. Missing data is an explicit error or a `missing` entry, never an origin flag.
- **Exact IDs only**: `item=` and `id=` accept a full `namespace:path` or `#namespace:tag` through `ItemQuery`/`ItemFilter`. Do not add substring or display-name matching to a collector; display names are resolved through `registry_search`.
- **Two-Phase Threading**: capture live game state on the owning thread through `MainThreadExecutor` (`world/`, `player/`, `advancement/`), then serialize off-thread. Reload-scoped indexes (`registry/`, `recipe/`, `worldgen/`, `loot/`, `datamaps/`, `trade/`, `guides/`, `status/`'s mod list) run behind `GenerationalCache` stamped by `CacheGenerations`, and reloadable world data is read through `data/DataPackView`. Process-lifetime caches that do not change with a reload (`reflect/ClassIntrospector`'s per-class map, `metrics/PerfMetrics` counters) are plain concurrent maps instead.
- **One JSON conversion**: serialize Minecraft objects with `serial/JsonCodec` (`serializeItemStack`, `snapshotObject`, `nbtToJson`), so an `ItemStack` or a reflective snapshot has the same shape everywhere.
- **Adding a built-in mod parser**:
  1. Add a new parser under `mcp-server/src/parsers/modParsers/` implementing `BuiltinModParser` (`mcp-server/src/parsers/modParsers/types.ts`).
  2. Pin `modVersionRange` strictly using `semver` to the tested mod version range — never guess across untested major/minor versions.
  3. Register it in `mcp-server/src/parsers/modParsers/index.ts`.
  4. Report unexplained input through the shared `unparsedRecipeFlag`/`hasUnparsedFields` path (see `mcp-server/src/parsers/shared.ts`), so the `rawJson` retention rule in `parsers/retention.ts` keeps the source payload.
  5. Add unit tests in `mcp-server/tests/modParsers.test.ts` covering both valid parsing and `parserVersionMismatch` behavior.

## Version Sync

When bumping the release version, keep these two declarations synchronized:

1. `mod/stonecutter.properties.toml` (`mod.version`)
2. `mcp-server/package.json` (`version`)

Neither side keeps a copy in code. The mod asks its loader for its own version (`IPlatformHelper.getModVersion()`), which the build expands into `fabric.mod.json` / `neoforge.mods.toml` from `stonecutter.properties.toml`. The MCP server calls `readPackageVersion()` (`mcp-server/src/version.ts`), which reads `package.json` at runtime.

## Pull Request Checklist

- [ ] `pnpm typecheck`, `pnpm build`, and `pnpm test` pass in `mcp-server/`
- [ ] `./gradlew :common:<mc_version>:test :neoforge:<mc_version>:build :fabric:<mc_version>:build` passes in `mod/`
- [ ] `pnpm e2e` passes for the area you changed (CI runs the `quick` preset; releases run `release`)
- [ ] A changed endpoint or response shape is reflected in `mcp-server/src/api/` (the contract test enforces this)
- [ ] Documentation (`README.md`, `README.ja.md`, `docs/ARCHITECTURE.md`, `CHANGELOG.md`) updated if user-facing behavior or tools changed
- [ ] Changes keep the mod 100% read-only, client-only HTTP (`127.0.0.1`), and privacy-scoped on multiplayer sync

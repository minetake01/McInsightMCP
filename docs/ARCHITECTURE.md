# McInsightMCP Architecture & Protocol Specification

This document describes the design as the code declares it. Where a behavior could not be confirmed
from source, this document says so instead of describing an expectation.

## 1. Repository Layout

```text
McInsightMCP/
├── docs/
│   └── ARCHITECTURE.md     # This document: contract, package map, transports, MCP server
├── mod/                    # Stonecutter Gradle project: one node per (loader, Minecraft version)
├── mcp-server/             # TypeScript MCP server (Streamable HTTP & stdio), MCP spec 2026-07-28
└── bench/                  # Main-thread MSPT and endpoint latency benchmark harness
```

---

## 2. Design: One Contract, Two Thin Transports

The mod publishes a small read-only introspection contract, and both transports are adapters over it:

- The loopback HTTP server accepts requests from local MCP clients.
- The multiplayer packet bridge accepts the same requests from a connected client when the server also
  runs McInsight.

Everything that defines the protocol is declared once, in code:

| Concern | Declared in | Property |
|---|---|---|
| Endpoints | `mod/.../api/InsightEndpoint.java` | Id, HTTP path, data scope, player scope, time budget, accepted parameters, page bounds |
| Errors | `mod/.../api/InsightError.java` | Code, HTTP status, default message |
| Paging | `mod/.../api/Page.java` | One counted shape and one uncounted shape |
| Missing composite parts | `mod/.../api/PartialData.java` | `{partial: true, missing: [{part, code, error}]}` |
| Parameter parsing | `mod/.../api/Params.java` | Strict typed reads; unknown keys rejected |
| `item=` semantics | `mod/.../api/ItemQuery.java`, `ItemFilter.java` | Exact id or `#tag`, never a substring |
| Routing | `mod/.../api/InsightDispatcher.java` | Resolves a handler and decides who can answer |
| Wiring | `mod/.../InsightHandlers.java` | Binds every endpoint to its handler |

Consequences of that single declaration:

- A parameter an endpoint does not declare is rejected (`INVALID_PARAMETER`) instead of being ignored
  by a handler that never read it. Both transports run the same check, so a bridged request is held to
  the same table as a loopback one.
- Page size bounds live with the endpoint. A `limit` above the endpoint maximum is an error, not a
  silently shortened page.
- HTTP status follows from the error code, so a client can branch on the status without parsing prose.
- The multiplayer bridge and the loopback server cannot drift in what they accept or in how they
  describe a failure.

### 2.1 Data scope and player scope

Every endpoint declares two scopes:

`DataScope` — where the authoritative values live:

| Scope | Meaning |
|---|---|
| `LOCAL` | Answerable from the client alone (registries, recipes, guides, trades table, player, target). |
| `SERVER_DATA` | Only the world owner has the data (worldgen definitions, loot tables, data maps, advancement progress). |
| `COMPOSITE` | Built from local data plus optional server-owned parts; a part that cannot be read is reported, not dropped (`looking_at`, `scan`). |

`PlayerScope` — which player a query is about:

| Scope | Meaning |
|---|---|
| `NONE` | Not player-relative. |
| `LOCAL` | The player at this client. |
| `REQUESTER` | The player who issued a bridged request. |

`InsightDispatcher.exposedToRemoteClients(endpoint)` is true when the scope is not `LOCAL` **or** the
player scope is `REQUESTER`. That predicate is what the packet bridge allowlists, and it is what the
server advertises in its hello packet; it is a derived rule, not a second hand-maintained list.

### 2.2 How server-owned data reaches a client

`InsightDispatcher` is the only place that knows a client may have to ask the owner of the world:

1. If an endpoint is `SERVER_DATA` and this client hosts the world (an integrated server is active),
   the integrated server is authoritative and the handler runs locally.
2. Otherwise, if a bridge handshake with the connected server completed, the whole request is
   delegated over the packet bridge and the answer is returned as if it had been local.
3. Otherwise the handler runs against what this client itself holds, and an input it cannot reach is
   reported instead of being papered over. The code paths that do this:
   - `DataPackView.requireAvailable()` fails `SERVER_MOD_REQUIRED` when the session has neither a
     registry view nor a data resource manager (for example, no world loaded).
   - `LootTableSource` fails `DATA_UNAVAILABLE` when the loot-table registry is not visible in this
     session or the `LOOT_TABLE` lookup is absent.
   - `DataMapQuery` fails `DATA_UNAVAILABLE` when the session cannot read datapack files, and
     `UNSUPPORTED_ON_LOADER` on a loader without NeoForge data maps.
   - `WorldgenQuery` / `WorldgenData` read whichever of dynamic registries and datapack files the
     view has: `worldgen/summary` reports `hasDynamicRegistries` and `hasDataResources` and counts
     what the view can see (a registry-backed kind is skipped only when the session has no registries,
     and `biome_modifier` is skipped without datapack resources or on a loader that has no such
     concept), while `worldgen/query` fails `DATA_UNAVAILABLE` when the requested kind has neither
     registry entries nor reachable datapack files.
   - Guide list and search answers add a `missing` entry `datapack_guides` (`DATA_UNAVAILABLE`) when
     the session cannot read datapack files, because books shipped as data would otherwise be absent
     without a trace.
   - Composite parts (`looking_at` block-entity contents, `scan` block-entity contents) add a
     `missing` entry with `SERVER_MOD_REQUIRED` when no bridge is available.

   `InsightDispatcher` delegates only once `ServerSync.isAvailable()` is true (a `hello` carrying the
   same protocol version arrived). Before that point the handler runs locally, which is why the
   explicit errors above are what "the server does not run McInsight" looks like on a bare
   multiplayer client. A delegated request that fails surfaces as `SERVER_MOD_REQUIRED` (the bridge
   became unavailable), `REMOTE_TIMEOUT`, or `REMOTE_ERROR`, never as a locally computed answer.

> Data origin is deliberately not part of any response. There are no fields stating that a value came
> from the server or from the client: whether it traveled, and over which path, is an implementation
> detail and never a second thing for a caller to reason about.

### 2.3 Absent by design

Three things a response deliberately does **not** have, because each one would make the caller
reason about the implementation instead of the data:

- **No provenance.** Nothing states whether a value was read locally or fetched from the world owner.
  There is no "authoritative server data" flag and no source marker distinguishing a registry-derived
  value from a resource-derived one; the only `source` values anywhere are the guide-system names on
  guide rows.
- **No per-collector "no data" vocabulary.** Every data kind reports absence through the same two
  codes (`DATA_UNAVAILABLE`, `SERVER_MOD_REQUIRED`), so a caller never has to enumerate one code per
  collector to know "this cannot be read here".
- **No per-endpoint page shape.** All paginated endpoints answer the one envelope in section 6.1, and
  per-viewer text appears only in the per-viewer map (`viewerInformation`), never as a flattened
  single-source field on the entry.

---

## 3. Mod Package Map

```text
mod/common/src/main/java/net/minetake/mcinsight/
├── api/          # The contract: endpoint table, error catalogue, Params, Page, PartialData,
│                 #   ItemQuery/ItemFilter, RequestContext, dispatcher, handler interface
├── game/         # Thread hand-off (MainThreadExecutor) and session object access (GameAccess)
├── cache/        # Generation counters and generational caches
├── data/         # Datapack/registry snapshot (DataPackView), server sync (ServerSync), server parts
├── serial/       # The single JSON conversion (JsonCodec)
├── metrics/      # Per-endpoint and main-thread request accounting (PerfMetrics)
├── http/         # Loopback HTTP transport only
├── network/      # CustomPacketPayloads, chunking, shared networking definition
├── compat/       # Viewer-neutral model, viewer adapter interface + registry, JEI/EMI/REI adapters
├── client/       # Client-only accessor (level, player, screen, language, resources)
├── config/       # mcinsight.json model and the resolved operator caps (Limits)
├── platform/     # Loader abstraction used by the common code
├── recipe/       # Recipe manager index, viewer query semantics, viewer JSON, image rendering
├── registry/     # Registry index, search, inspect, tags, display names
├── guides/       # Guide/quest/lang index and queries
├── worldgen/     # Worldgen definitions and queries
├── loot/         # Loot table index, modifier index, loot JSON logic
├── datamaps/     # NeoForge data map index and query
├── world/        # Raycast target, area scan, block-entity parts, shared world vocabulary
├── advancement/  # Advancement definitions + player progress
├── trade/        # Villager/wandering-trader offer extraction
├── status/       # Status, mods, metrics answers
├── player/       # Player state
├── reflect/      # Runtime class introspection
├── render/       # Recipe drawing: an off-screen target on 1.21.x, the main target as scratch on 26.x
├── InsightHandlers.java  # Endpoint -> handler binding
├── McInsightCommon.java  # Lifecycle: HTTP start, cache generations, server attach/detach
└── McInsightConstants.java
```

Rules that hold across packages:

- `http/` contains no protocol knowledge: it parses the query string, resolves the endpoint from the
  table, calls the dispatcher, and writes the result.
- `api/` holds the protocol itself; the only game-facing code in it is ID/kind validation
  (`ItemQuery`, `ItemFilter` resolve tags against the item registry) and `RequestContext` holding the
  requester. No handler logic lives there.
- Handlers throw `InsightException`; they never build an error object themselves.
- Anything that reads live game state goes through `game/` (`MainThreadExecutor`, `GameAccess`).
- Anything that reads reloadable world data through the datapack/registry snapshot goes through
  `data/DataPackView`, so worldgen, loot tables, loot modifiers, and data maps share one snapshot, one
  generation stamp, and one JSON cache. Guides read the asset/data resource managers through
  `guides/GuideIndex`, which keeps its own per-generation index and content caches; trades read the
  game's trade system through `trade/TradeCollector`, whose two readers (`readRegistryTrades` for
  26.1 and later, `readListingTrades` up to 1.21.1) are version-exclusive branches of one file.

Loader modules bind the same common code:

```text
mod/neoforge/...  # @Mod entrypoint, payload registration, reload listeners, client events
mod/fabric/...    # Fabric entrypoints; shared payload registration in McInsightFabricCommon
```

The build is a Stonecutter tree with one branch per module (`common`, `fabric`, `neoforge`) and one
node per Minecraft version in each branch. `:common:<version>` owns the shared `src/`, and
`:fabric:<version>` / `:neoforge:<version>` compile that branch's preprocessed output for the same
version; the version list is `minecraft_versions` in `mod/gradle.properties` and each version's pins
are in `mod/stonecutter.properties.toml`. A difference in a name is a Stonecutter replacement, a
difference in shape is a Stonecutter comment (`//? if >=26.1 {`), and a difference in whole files is
a `files { disable(...) }` rule — never a copy of the file. Names the versions still share live in
`game/Versioned`, which is where a rename is answered once.

---

## 4. Endpoint Table

HTTP endpoints are served under `/api/v1/` and GET-only. `limit`/`offset` exist only where the table
declares page bounds.

| Id | HTTP path | Parameters | Data scope | Player scope | Page bounds |
|---|---|---|---|---|---|
| `status` | `/api/v1/status` | `includeMods` | LOCAL | LOCAL | — |
| `mods` | `/api/v1/mods` | — | LOCAL | NONE | — |
| `metrics` | `/api/v1/metrics` | — | LOCAL | NONE | — |
| `player` | `/api/v1/player` | — | LOCAL | LOCAL | — |
| `looking_at` | `/api/v1/looking-at` | `maxDistance`, `includeFluids` | COMPOSITE | LOCAL | — |
| `scan` | `/api/v1/scan` | `radius`, `filter` | COMPOSITE | LOCAL | — |
| `registry_search` | `/api/v1/registry/search` | `kind`, `q`, `mod`, `limit`, `offset` | LOCAL | NONE | 50 / 200 |
| `registry_inspect` | `/api/v1/registry/inspect` | `kind`, `id` | LOCAL | NONE | — |
| `registry_tag` | `/api/v1/registry/tag` | `kind`, `id`, `limit`, `offset` | LOCAL | NONE | 200 / 500 |
| `recipes_types` | `/api/v1/recipes/types` | — | LOCAL | NONE | — |
| `recipes_query` | `/api/v1/recipes/query` | `item`, `role`, `type`, `mod`, `source`, `limit`, `offset` | LOCAL | NONE | 40 / 100 |
| `recipes_render` | `/api/v1/recipes/render` | `item`, `role`, `type`, `source`, `limit`, `offset` | LOCAL | NONE | 1 / 8 |
| `guides_list` | `/api/v1/guides/list` | `source`, `mod`, `limit`, `offset` | LOCAL | NONE | 50 / 500 |
| `guides_search` | `/api/v1/guides/search` | `q`, `source`, `mod`, `lang`, `includeAllLang`, `limit`, `offset` | LOCAL | NONE | 20 / 50 |
| `guides_page` | `/api/v1/guides/page` | `id` | LOCAL | NONE | — |
| `worldgen_summary` | `/api/v1/worldgen/summary` | — | SERVER_DATA | NONE | — |
| `worldgen_query` | `/api/v1/worldgen/query` | `kind`, `q`, `mod`, `searchContent`, `minTemp`, `maxTemp`, `summaryOnly`, `limit`, `offset` | SERVER_DATA | NONE | 30 / 100 |
| `loot_query` | `/api/v1/loot/query` | `q`, `item`, `mod`, `includeModifiers`, `limit`, `offset` | SERVER_DATA | NONE | 30 / 100 |
| `datamaps_query` | `/api/v1/datamaps/query` | `q`, `registry`, `mod`, `includeRaw`, `limit`, `offset` | SERVER_DATA | NONE | 30 / 100 |
| `trades_query` | `/api/v1/trades/query` | `item`, `q`, `profession`, `mod`, `limit`, `offset` | LOCAL | NONE | 40 / 200 |
| `advancements_query` | `/api/v1/advancements/query` | `q`, `mod`, `status`, `includeRecipes`, `limit`, `offset` | SERVER_DATA | REQUESTER | 40 / 200 |
| `introspect_class` | `/api/v1/introspect/class` | `name` | LOCAL | NONE | — |

Two endpoints exist for the bridge only and carry no HTTP path. Both are `SERVER_DATA` and scoped to
the `REQUESTER`:

| Id | Parameters | Data scope | Player scope | Purpose |
|---|---|---|---|---|
| `server_looking_at_block_entity` | `x`, `y`, `z` | SERVER_DATA | REQUESTER | One block position's block entity contents, for a `looking_at` composite answer |
| `server_scan_block_entities` | `radius`, `filter` | SERVER_DATA | REQUESTER | Block entity contents around the requesting player, for a `scan` composite answer |

`trades_query` accepts both `item` (exact id or `#tag`, matched on either side of the offer) and `q`
(free text over the materialized offer). `trades_query` is `LOCAL`: offers are materialized from the
game's own `ItemListing` implementations, which the client also has.

The caps below are **operator settings, not compiled constants**: `McInsightConfig.loadOrCreate()`
validates `mcinsight.json` and hands the values to `config/Limits`, and every collector reads its bound
from there, so a change applies to the side that serves the data (including the server side of a bridged
request). `Limits.apply` clamps each value to its own hard maximum and the collectors re-read it through
an accessor:

- `InsightEndpoint.effectiveMaxLimit()` caps `recipes_query` at `min(endpoint max 100,
  Limits.recipeResults())` — i.e. `maxRecipeResults` (default 100, hard max 500) can only lower the
  endpoint ceiling, never raise it above 100.
- `AreaScanner.maxScanRadius()` is `Limits.scanRadius()` — `maxScanRadius` (default 16, hard max 32).
  A request above it is clamped and answers the clamped `radius`, plus `requestedRadius` and a `missing`
  entry for the `radius` part, instead of failing.
- `TargetCollector.maxRaycastDistance()` is `Limits.raycastDistance()`, which is fixed at 64 (64 is also
  `HARD_MAX_RAYCAST_DISTANCE`; there is no config key). `maxDistance` below 1.0 is
  `INVALID_PARAMETER`, above 64 it is silently clamped to 64.
- `BlockEntityPart.maxSyncBlockRadius()` is `Limits.syncBlockRadius()` — `maxSyncBlockRadius`
  (default 16, hard max 16). A `scan` wider than it reports `blockEntityRadius` and asks the server for
  the inner square; a `looking_at` target outside it records an `OUT_OF_RANGE` missing part without
  asking the server.

---

## 5. Error Catalogue and HTTP Status

Every failure is `{ "ok": false, "code": "<InsightError name>", "error": "<message>" }`. The HTTP
status is the code's declared status; `HttpServer` looks it up rather than deciding per call site.

| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_PARAMETER` | 400 | A query parameter has an invalid value (including a parameter the endpoint does not declare). |
| `INVALID_QUERY` | 400 | Malformed percent-encoding in the query string, or a missing required search term (`guides_search` `q`). |
| `INVALID_ID` | 400 | A required ID parameter is missing/blank, or an `item=`/`id=` value is not a full id or `#tag`. |
| `INVALID_KIND` | 400 | The supplied kind is not supported by this endpoint. |
| `INVALID_SOURCE` | 400 | The supplied `source` is not supported by this endpoint. |
| `INVALID_CLASS` | 400 | The class name is missing or malformed. |
| `UNSUPPORTED_ON_LOADER` | 400 | The data does not exist on the active loader (biome modifiers, Global Loot Modifiers, data maps). |
| `UNAUTHORIZED` | 401 | Missing or invalid bearer token. |
| `UNSUPPORTED_ENDPOINT` | 404 | Unknown endpoint. |
| `NOT_FOUND` | 404 | The requested entry/guide-page/entity/block does not exist. |
| `CLASS_NOT_FOUND` | 404 | The class is not loaded. |
| `METHOD_NOT_ALLOWED` | 405 | Only GET is supported. |
| `NO_WORLD` | 409 | No world or player is loaded, or the requester is not present on the world owner. |
| `OUT_OF_RANGE` | 400 | The target is outside the inspection range (block-entity sync radius). |
| `RATE_LIMITED` | 429 | Too many bridged requests in the rate window (20 per player per second). |
| `DATA_UNAVAILABLE` | 503 | The requested data is not available in the current session. |
| `SERVER_MOD_REQUIRED` | 503 | The server does not run McInsight, so server-side data cannot be read. |
| `VIEWER_UNAVAILABLE` | 503 | The requested recipe viewer is not active. |
| `REMOTE_ERROR` | 502 | The server failed to answer a bridged request. |
| `REMOTE_TIMEOUT` | 504 | The server did not answer in time. |
| `INTERNAL_ERROR` | 500 | Unexpected failure. |

Failure rules:

- A handler throws; it never returns an error body.
- A bridged failure carries the same code. A code the receiving client does not know is mapped to
  `REMOTE_ERROR` rather than being collapsed into a generic failure.
- A composite answer that is missing a part returns `200` with `partial: true` and the `missing`
  entries; it is not a failure of the whole answer.
- The MCP server adds MCP-side failures to the same vocabulary only where the mod cannot answer at
  all: `CONNECTION_ERROR` when the client cannot reach the mod, plus `INVALID_JSON_RESPONSE` /
  `INVALID_RESPONSE` for a reply that does not carry `ok`. A mod failure always keeps the mod's code.

---

## 6. Response Shapes

### 6.1 Paging

Every paginated endpoint answers with one envelope:

```json
{
  "ok": true,
  "offset": 0,
  "totalKnown": true,
  "total": 1234,
  "returned": 50,
  "hasMore": true,
  "<domainArrayKey>": []
}
```

- `total` is present exactly when `totalKnown` is `true`.
- `returned` is the number of items in this page; `hasMore` is computed from `offset + returned`
  against the total (or, for an uncounted page, from whether the scan stopped because the page was
  full).
- `totalKnown: false` is a distinct state, not `total: 0`. Recipe-viewer pages are uncounted
  (`Page.uncounted`) because counting them would mean scanning every viewer category.
- The array key is domain-specific: `results`, `members`, `guides`, `hits`, `entries`, `recipes`,
  `trades`, `advancements`.
- An endpoint may add scalar fields beside the envelope (for example `registry_tag`'s `kind`/`id`,
  `worldgen_query`'s `kind`, `guides_search`'s `query`/`lang`), but the page fields themselves are
  never renamed or dropped.
- `recipes_query` is the one multi-page answer: `vanilla` and each entry of `viewers` (keyed by
  viewer name) carry their own envelope. It has no top-level page.
- `loot_query` is the one endpoint with a second array: it pages `entries` and returns
  `lootModifiers` whole with `totalModifiers`.

### 6.2 `item=` and `id=` are exact

`ItemQuery.parse` accepts exactly two forms:

- a full resource id, e.g. `minecraft:stone` or `tofucraft:tofudried`;
- a tag, e.g. `#minecraft:planks`.

Anything else (`stone`, a display name, a partial id) is `INVALID_ID`. Names are resolved first via
`registry_search`, which matches ids and localized display names and returns the exact id.

`ItemFilter` gives that one value its meaning everywhere (`recipes_query`, `loot_query`,
`trades_query`, `datamaps_query`, and the MCP item-flow tool):

- An exact id matches that item, and also matches a slot that accepts a tag the item belongs to.
- A `#tag` query matches the tag itself and every member of it.
- Recipes the index could not describe structurally are matched by searching their encoded JSON for
  the resolved id, so the rule stays "one exact id in, matching recipes out" — never a display-name or
  free-text match. Note that `source=...&item=...` is still an id query, not a text search.

The same exactness applies to `registry_inspect.id`, `registry_tag.id`, `guides_page.id`, and
`introspect_class.name`; each validates its own ID form and answers `INVALID_ID`, `NOT_FOUND`, or
`CLASS_NOT_FOUND` accordingly. `guides_page`'s `id` is an index key rather than a `ResourceLocation`,
so its validation is "not blank" plus an indexed lookup.

### 6.3 Composite/missing data

`PartialData.applyTo` adds two fields only when something is missing:

```json
{ "partial": true, "missing": [{ "part": "server_scan_block_entities", "code": "SERVER_MOD_REQUIRED", "error": "..." }] }
```

`part` names the fragment (an endpoint id for server parts, `radius` for a bounded field, or
`datapack_guides` for the guide documents only the world owner can read). There is no provenance field: the presence of an entry says what could not be
read, not where the readable parts came from.

### 6.4 Per-endpoint answers

**`status`** — `ok`, `modId`, `modVersion`, `minecraftVersion`, `loader`, `loaderVersion`, `port`,
`gameDir`, `modsDir`, `configDir`, `serverActive` (this client hosts the world), `worldLoaded`, `languageCode`
(the language the player at this client reads), and when a world is loaded `dimension`, `difficulty`, `dayTime`, `gameTime`,
`isRaining`, `isThundering`. `integrations` carries `recipeViewers` (every registered adapter mapped
to whether it can answer now), `activeRecipeViewers`, `patchouliLoaded`, `guidemeLoaded`,
`ftbQuestsLoaded`, `createLoaded`, `serverSyncAvailable`. `modCount` is always present; `mods` only
with `includeMods=true`.

**`mods`** — `modsDir`, `count`, `mods[]` with `id`, `name`, `version`, optional `description`,
optional `jarPath` (nested Jar-in-Jar paths included).

**`metrics`** — `serverTick` (`sampleTicks`, `avgMspt`, `p95Mspt`, `maxMspt`, `estimatedTps`) when a
server is active, and `endpoints` keyed by path with `count`, `cacheHits`, `avgTotalUs`,
`maxTotalUs`, `avgMainThreadUs`, `maxMainThreadUs`.

**`player`** — identity, `dimension`, `biome`, `environment` (`biomeBaseTemperature`, `canSeeSky`,
`skyLight`, `blockLight`, `precipitation`), `pos`, `blockPos`, `rotation`, vitals
(`health`/`maxHealth`/`absorptionAmount`/`foodLevel`/`saturation`/`airSupply`/`maxAirSupply`/
`xpLevel`/`experienceProgress`), `gameMode` where known, `isCreative`, `isSpectator`, optional
`vehicle`, `effects[]`, `mainHand`, `offHand`, `armor`, `selectedHotbarSlot`, `inventory[]` (non-empty
main-inventory slots with their slot index), and `openScreen` with `containerSlots` when a container
screen is open. A missing player is `NO_WORLD`.

**`looking_at`** — `hitType` is `miss`, `block`, or `entity`, plus `maxDistance` and `dimension`. A
block answer adds the block's `id`, `modId`, `displayName`, `blockClass`, `pos`, `properties`, `fluid`
when present, `tags`, `face`, and `blockEntity` when its contents could be read. An entity answer adds
`id`, `modId`, `displayName`, `entityClass`, `uuid`, `pos`, optional health, optional `itemStack` for
an item entity, and `nbt`.

**`scan`** — `dimension`, `radius`, `requestedRadius` when clamped, `blockSummary[]` (top 100 by
frequency) with `totalBlockTypes` and `blockSummaryTruncated`, `nearbyEntities[]` with
`totalNearbyEntities` and `nearbyEntitiesTruncated`, `blockEntities[]` with `totalBlockEntities` and
`blockEntitiesTruncated`, `blockEntityRadius` when the contents cover less than the scan, and
`matchedBlocks[]` when `filter` is set. A filter matches ids and display names; it is a scan filter,
not an `item=` selector.

**`registry_search`** — `kind` defaults to `item`; `item`, `block`, `fluid`, and `entity` search
registry entries, while `tag`, `item_tag`, `block_tag`, `fluid_tag`, and `entity_tag` search tag
names. Entry rows carry `id`, `modId`, `displayName` (when one exists), and `javaClass`; tag rows carry
`id` (with the leading `#`) and `kind` (`<kind>_tag`). `q` matches id or display name as a substring;
`mod` matches the namespace.

**`registry_inspect`** — `id` required, `kind` defaults to `auto`. `auto` checks item, block, fluid,
then entity; more than one match adds `alsoInRegistries`. The answer always carries `kind`, `id`,
`modId`; item answers add `itemClass`, `defaultComponents`, optional `foodProperties`, `blockInfo` for
a `BlockItem`; block answers add `blockInfo` and `blockItemId`; fluid answers add `fluidClass`,
`isSource`, `bucketItem`, `blockId`, `tags`; entity answers add `entityTypeClass`, `category`,
`width`, `height`, `fireImmune`, `canSummon`, `tags`. `viewerInformation` maps each active viewer name
to the description lines it returned; viewers with nothing to say are omitted. A missing id is
`NOT_FOUND`; a kind outside the item/block/fluid/entity set is `INVALID_KIND`.

**`registry_tag`** — `id` required (a leading `#` is accepted), `kind` defaults to `auto`. `auto`
looks the tag up in item, block, fluid, and entity and reports every kind that declares it as
`matchedTagKinds`; an explicit kind also accepts the `_tag` suffix. The answer carries `kind`
(`<kind>_tag`) and `id` (`#...`) alongside the page of `members` rows (`id`, `modId`, `displayName`).

**`recipes_types`** — `recipeManagerAvailable`, `recipeTypes[]` (`id`, `modId`, `count`),
`activeViewers[]`, and `viewers` keyed by viewer name, each with a category count and categories
(`uid`, `modId`, `title`, `workstations[]`).

**`recipes_query`** — `source` accepts `all` (default), `vanilla`, `viewer`, or the name of any
registered viewer adapter; a name no adapter registered is `INVALID_SOURCE`, and a registered but
inactive viewer is `VIEWER_UNAVAILABLE`. No viewer name is written in the query code: the registry
answers. `role` is `any` (default), `input`, `output`, `catalyst`, or `workstation`; any other value is
`INVALID_PARAMETER`. `type` matches a `RecipeType` id or viewer category uid; `mod` matches the recipe
type's namespace or the recipe id's namespace. The answer carries `vanilla` when the recipe manager
was asked and `viewers` keyed by viewer name when at least one viewer answered. Each is the page shape
with key `recipes`; viewer pages are uncounted (`totalKnown: false`). Catalysts and workstations exist
only in viewers, so for those roles `vanilla` is an empty counted page. `source=vanilla` with no recipe
manager is `DATA_UNAVAILABLE`; so is `source=all` when neither the recipe manager nor any viewer can
answer.

Viewer recipes use the shared model: `viewer`, `categoryUid`, optional `categoryTitle`/`modId`/
`recipeId`/`sourceRecipeIds`/`recipeClass`, `slotGranularity: "slots" | "flat"`, `inputs`/`outputs` as
arrays of slots where a slot is `{alternatives: [ingredient]}`, `catalysts[]`, `workstations[]`,
optional `description[]`, and `rawJson` or `pojoSnapshot`. Ingredient `type` is `item`, `fluid`, or
`custom:<kind>`, with `id`, `displayName`, `modId`, `amount`, `tags`, `components` when known. Vanilla
recipes carry `recipeId`, `recipeType`, `modId`, `recipeClass`, optional `serializer`, `rawJson` or
`pojoSnapshot`, and `resultPreview`.

**`recipes_render`** — draws the recipes that match one exact `item` the way the viewer's own screen
draws them. `role` defaults to `output` (the recipes that make the item); `type` restricts the
search to one viewer category uid; `source` is `viewer` (default, every active viewer that can
paint) or a viewer name, and `vanilla` is `DATA_UNAVAILABLE` because the recipe manager has no GUI
layout to draw. Images are rendered at a fixed 2x pixel scale. The answer is the uncounted page
shape with key `images` (`totalKnown: false`, so no `total`), plus `format: "png"` and `scale`; each
row is `viewer`, `categoryUid`, optional `categoryTitle`/`recipeId`, `width`/`height` in GUI units,
and `png` (base64 PNG bytes). `width`/`height` are the layout size; the image is
`width*scale` by `height*scale`. A category the viewer cannot paint is a `missing` entry, and when
nothing at all could be drawn the first failure is returned as the response error — an empty
`images` page must mean "no recipe matched", never "rendering is broken".

The image is produced without reading the player's screen. On `1.21.x` the GUI is drawn immediately,
so the viewer paints into a dedicated off-screen target and only that target's colour texture is
read. On `26.x` the GUI is recorded into a render state and drawn by the engine's `GuiRenderer`,
which only targets the main render target: that target is cleared and used as a scratch surface
between frames, the recipe is drawn at its top-left, only that rectangle is copied out, and the next
frame clears the target again before it draws. Nothing is read before it has been cleared and
painted, so the result is exactly the recipe rectangle and never the player's frame.

The request never blocks the client render thread on the answer. Each drawing is started by one
short client-thread task, one drawing per frame: prepare the viewer layout, record it, and issue the
read-back. The copy is the engine's fenced read-back, so it completes asynchronously; the thread
answering the request waits for the pixels, then resizes and PNG-encodes them off the render thread.
The residual render-thread cost per requested image is the layout preparation plus the fenced copy
of the recipe rectangle (a few milliseconds, only while an image is being requested); a request for
several images is spread across frames so no frame carries all of them. `REI` painting is `1.21.x`
only: its `26.x` display-renderer API is not on this build's classpath, so a `26.x` REI client lists
recipes normally but does not draw them.

This is a client-only operation (the loopback server exists only on a client, and a dedicated server
never starts it), which is why the endpoint is `LOCAL`; the player's GUI scale does not affect the
output, because the image is resampled to the fixed 2x scale.

**`worldgen_summary`** — `hasDynamicRegistries`, `hasDataResources`, `counts` keyed by kind(`placed_feature`, `configured_feature`, `biome`, `structure`, `dimension_type`, `biome_modifier`).
Registry-backed kinds are counted only when the session has dynamic registries, and `biome_modifier`
only when it has datapack resources on a loader that defines biome modifiers; a kind that cannot be
counted here is absent from `counts` instead of appearing as `0`.

**`worldgen_query`** — `kind` defaults to `placed_feature`; `q` matches the entry id and, with
`searchContent=true`, the entry's encoded JSON; `mod` matches the namespace; `minTemp`/`maxTemp` and
`summaryOnly` apply to `kind=biome` (the summary keeps temperature, downfall, precipitation,
temperature modifier, and spawners); page key is `entries` (`id`, `modId`, `kind`, `rawJson`). The
answer repeats `kind`. `biome_modifier` is NeoForge-only (`UNSUPPORTED_ON_LOADER` elsewhere); an
unknown kind is `INVALID_KIND`.

**`loot_query`** — `q` matches a table's id and, for the modifier half, a modifier's id or payload;
`mod` the namespace; `item` is a reverse lookup over what a table can drop, following nested table
references up to three hops. **The two halves page differently:** `entries` (loot tables) is the
paged array carrying `offset`, `totalKnown`, `total`, `returned`, and `hasMore` like every other
paginated endpoint, while `lootModifiers` is returned **whole** and accompanied only by
`totalModifiers`. The modifier half has no page and therefore no second "more available" flag: the
active modifier set is the small list the merged `global_loot_modifiers` layers name, and slicing it
with the table page's `offset` would make paging tables silently shift the modifier window. Omitting
`includeModifiers` means "include them where the loader has them"; asking for them explicitly on
Fabric is `UNSUPPORTED_ON_LOADER`. Modifier entries carry `id`, `modId`, `type`, `injectedTable`,
`targetTables`, and `rawJson`.

**`datamaps_query`** — NeoForge only (`UNSUPPORTED_ON_LOADER`). `q` matches the definition id, a value
key, or a value's JSON; `item` (the `ItemFilter` rule) selects values whose key is that entry or a
tag containing it, and never looks inside a value; `registry` matches a substring of the derived registry (`item`, `block`,
`custommod:machine_fuel`, …); `mod` the namespace. Entries carry `id`, `modId`, `registry`,
`totalValueCount`, and, for a `q` or `item` query, `matchedValues` (up to 50 per entry),
`totalMatchedValues`, `matchedValuesTruncated`. `rawJson` is included when `includeRaw=true`, when
neither `q` nor `item` is set, or when the definition holds at most 100 values.

**`trades_query`** — offers come from the version's own trade system: up to 1.21.1 each profession
holds a table of `ItemListing` objects, and from 26.1 the trades are named registry entries grouped
into a `TradeSet` per profession and level. Either way the offer is produced by the game —
`ItemListing.getOffer` against a detached villager, or `VillagerTrade.getOffer` against the same loot
context a villager builds — so price and enchantment randomness land the way the game applies them. A
listing holding a villager-type map is expanded per type (a biome wrapper is unwrapped first, so the
listing that actually runs is what gets checked); a 26.x trade, which limits itself to villager types
with a loot condition, is rolled once per type and reported against the types it survives. An offer
whose result depends on randomness is sampled twice and marked `sampled`. Listings are recognised by
the values they hold rather than by class name, because vanilla's listing classes are package-private
and renamed by loader mappings. A listing that names a structure tag would search the world for a map
target, which is unbounded work on the server thread and a write into map storage, so it is reported
by its reflected declaration instead; so is a listing that fails to materialize. Entries carry
`profession`, `level` (absent where the game does not level the trade, as for the wandering trader),
optional `experimental`, `listingClass`, `tradeId` (26.x only — the registry entry the trade is
defined by), `costA`/`costB`/`result` (`id`, `displayName`, `count`, or `{"empty": true}`), `maxUses`,
`xp`, `priceMultiplier`, optional `sampled` and `villagerType`, or a reflected `listing` snapshot.
`item=` matches either side of the swap unless `side` is `cost` (the player pays with it) or `result`
(the player receives it); `side` without `item` is `INVALID_PARAMETER`. A declared-only listing has no
stacks, so `item=` never matches it; `q` still does, and matches profession, listing class, trade id,
stack ids and names.

**`advancements_query`** — entries carry `id`, `modId`, optional `parent`, display fields (`title`,
`description`, `frame`, `icon`, `hidden`), `criteria[]`, optional `criteriaDetails`, optional
`requirements`, optional `rewards`, and `progress` (`done`, `percent`, `completedCriteria`,
`remainingCriteria`). Definitions and progress both belong to the world owner, so the endpoint is
`SERVER_DATA`: it runs where a server is active, and a client whose server does not run McInsight
gets `SERVER_MOD_REQUIRED` rather than a definitions-only page that would read as "nothing
completed". Recipe-unlock advancements under `recipes/` are excluded unless `includeRecipes=true`.

**`guides_list` / `guides_search` / `guides_page`** — sources are `patchouli`, `guideme`,
`ftbquests`, `ponder`, `oracle`, `lang`; `source=all` is the default and an unknown value is
`INVALID_SOURCE`. Rows carry `id`, `source`, `modId`, `path`; a language hit adds `lang`, `key`, and a
`snippet`, while a document hit adds a `snippet` taken around the match (or its path when only the id
matched). Search requires `q` (`INVALID_QUERY` when absent or blank), defaults to the active client
language, and `includeAllLang` widens language entries beyond documentation-like keys. `guides_page`
requires `id` (`INVALID_ID` only when the parameter is missing or blank); the lookup is
case-insensitive and an id that is not indexed is `NOT_FOUND`. It returns the row plus `content`.

**`introspect_class`** — `name` required (`INVALID_CLASS` when missing); answers the loaded class's
declared shape (`name`, `isInterface`, `isEnum`, `isAbstract`, `superclass`, `interfaces`,
`codeSource`, `declaredFields[]`, `declaredMethods[]` with parameter types). Synthetic members are
skipped. `CLASS_NOT_FOUND` when the class is not loaded. Results are cached per class name (bounded at
512 entries) because a loaded class's declared shape does not change.

### 6.5 Recipe viewer adapters

JEI, EMI, and REI are adapters over `ViewerModel`:

- An adapter enumerates categories and the recipes in a category, and answers per-ingredient info.
  It does not filter, page, or shape JSON.
- Filtering by ingredient/role/category/mod, paging, and JSON shaping happen once, in
  `recipe/ViewerQuery` and `recipe/ViewerJson`, so the same query selects the same recipes whichever
  viewer answers.
- The model states what a viewer cannot express: `slotGranularity` is `slots` when the viewer keeps
  slot boundaries and `flat` when it only supplies ingredient lists (JEI's rendering path can do the
  latter), so a caller compares structure explicitly instead of inferring it from the viewer name.
- `ViewerRegistry` holds the adapters and knows no viewer names; adapters announce theirs. The status
  endpoint and `recipes/types` enumerate whatever registered.
- An adapter is compiled only into the versions whose build pins its dependency: EMI has no `26.x`
  release, so those builds carry no EMI bridge and no EMI entrypoint. Adding a `deps.emi` pin for a
  version is what brings it back — nothing about the adapter is version-conditional in the source.
- An adapter may also implement `painter()`: it returns the viewer's own way of drawing a recipe
  (`createRecipeLayoutDrawable` for JEI, `addWidgets` for EMI, `getDisplayRenderer` for REI), or
  `null` when that viewer cannot draw. The recipe model keeps the viewer's own recipe object as an
  unlisted `nativeRecipe` field purely so the adapter that produced it can find it again; it is never
  serialized. `ViewerJson` and the query semantics know nothing about painting.

---

## 7. Transports

### 7.1 Loopback HTTP (client only)

```text
GET /api/v1/<endpoint>?<params>  ->  JSON (200, or the error code's status)
```

- Bound to `127.0.0.1` on `config.port` and the next `config.portRangeSize - 1` ports (defaults
  `25580` and `20`).
- A dedicated server never starts it: `McInsightCommon.initServer()` loads the config and logs, and
  `InsightHttpServer` is constructed only in `initClient()`.
- Only GET; anything else is `METHOD_NOT_ALLOWED`. A path that no endpoint declares is
  `UNSUPPORTED_ENDPOINT`; the bridge-only server parts have no HTTP path and are therefore also
  `UNSUPPORTED_ENDPOINT` over HTTP.
- When `authToken` is set, `Authorization: Bearer <token>` must match exactly, else `UNAUTHORIZED`.
- Error responses carry the code; the HTTP status is looked up from `InsightError`.
- Every response carries `X-McInsight-MainThread-Us`, `X-McInsight-Total-Us`, and
  `X-McInsight-Cache` (`hit`/`miss`).
- `Cache-Control: no-store`.

### 7.2 Multiplayer packet bridge

The client side is `data/ServerSync`; the server side is `network/ServerPacketHandler`. Both call the
same dispatcher and the same `Params` parser.

Payloads (`mcinsight:hello`, `mcinsight:request`, `mcinsight:response`):

| Payload | Direction | Contents |
|---|---|---|
| `hello` | server → client | protocol version (1), loader name, comma-separated capability list |
| `request` | client → server | request id, endpoint id, params as a JSON object string |
| `response` | server → client | request id, chunk index, chunk count, chunk bytes |

- The bridge becomes usable only after a `hello` carrying the same protocol version
  (`ServerSync.isAvailable()`). Before that the dispatcher does not delegate: a `SERVER_DATA` endpoint
  runs locally and fails through the collector-specific paths above, and a composite part is recorded
  as missing with `SERVER_MOD_REQUIRED`. `ServerSync.request` itself also refuses with
  `SERVER_MOD_REQUIRED` if called while the handshake is absent.
- `ServerPacketHandler` rejects an endpoint that is not `exposedToRemoteClients`
  (`UNSUPPORTED_ENDPOINT`) and rate-limits each player to 20 requests per second (`RATE_LIMITED`).
- Responses are chunked at 24 KiB with a 2 MiB / 128-chunk ceiling; a response that cannot be
  represented becomes a `DATA_UNAVAILABLE` error object rather than a truncated answer.
- A client waits at most `max(500 ms, endpoint budgetMs())`; a timeout is `REMOTE_TIMEOUT`, a transport
  failure is `REMOTE_ERROR`, and an unknown remote code maps to `REMOTE_ERROR`.
- Every bridged query is answered as the player who sent it for player-scoped endpoints
  (`PlayerScope.REQUESTER`); endpoints declared `NONE` are world-level and carry no player. No other
  player's progress or surroundings are reachable.

### 7.3 Client discovery and connection

`mcp-server/src/client/mcClient.ts`:

- Without an explicit URL, it probes `127.0.0.1:25580..25599` in parallel with a 500 ms abort per
  attempt and accepts the first port whose `/api/v1/status` reports `modId: "mcinsight"`.
- A 401 during probing surfaces as `UNAUTHORIZED` with a hint to pass `--token`/`MCINSIGHT_TOKEN`.
- A failed discovery is negatively cached for 5 s so repeated calls do not re-probe every time.
- `--mc-url` / `MCINSIGHT_URL` (or a `baseUrl` option) skips discovery entirely. This is required when
  the mod's `port` or `portRangeSize` is changed, because the probe range is the default range, not the
  configured one.
- `/api/v1/status` and `/api/v1/mods` are cached for 60 s for connection metadata (mods dir, game dir,
  loaded mod versions).

---

## 8. Threading, Caching, and Measurement

- **Game-thread hand-off** — `game/MainThreadExecutor` runs a supplier on the thread that owns the
  state: the integrated server thread, the client thread, or whichever is the game thread
  (`supplyOnGameThread`). It measures the time spent on that thread and reports it to `PerfMetrics`,
  which is where `X-McInsight-MainThread-Us` and the main-thread statistics come from.
- **Two-phase collection** — `world/`, `player/`, and `advancement/` copy the minimal live objects on
  the owning thread, then build JSON off-thread from the copies. The request thread never reads live
  game state.
- **Static/reload-scoped data** — the registry index, recipe index, worldgen summary, loot table and
  modifier indexes, data map index, trade index, guide index, and the mod list are built off-thread
  behind `GenerationalCache`, stamped by `CacheGenerations.data()` or `.resource()`. The index is
  rebuilt when its generation changes (datapack/resource reload or a world lifecycle transition),
  not on a timer. `ClassIntrospector` and `PerfMetrics` keep their own process-lifetime maps because
  their inputs (a loaded class's declared shape, per-endpoint counters) do not change with a reload.
- **Explicit invalidation** — loaders bump generations on datapack/resource reload listeners, server
  start/stop, client login/logout, and client start/stop (`McInsightCommon`); nothing enumerates the
  collectors. Viewer adapters hold their own category caches, dropped when the viewer (re)attaches
  (JEI `onRuntimeAvailable`, EMI/REI entrypoint registration), so no generation bump has to reach
  them.
- **Request metrics** — `metrics/PerfMetrics` counts per endpoint: request count, cache hits, average
  and maximum total time, and average and maximum main-thread time. It reads
  `MinecraftServer.getTickTimesNanos()` on demand for MSPT/TPS; it does not register per-tick
  listeners. A trace is opened and closed by the loopback HTTP transport, so the per-endpoint
  `count`/`avgTotalUs` figures describe HTTP traffic; main-thread time is attributed to the active
  trace on the calling thread, and a bridged request opened from `ServerPacketHandler`'s worker
  currently has no trace of its own.

---

## 9. MCP Server (`mcp-server/`)

The server is a Node.js (>= 20) TypeScript MCP server implementing spec `2026-07-28` over stateless
Streamable HTTP or stdio.

Package map:

```text
mcp-server/src/
├── api/            # Contract mirror: ENDPOINTS, error codes, result envelope/types, wire types
├── data/           # One module per data kind (worldState, registry, recipes, loot, worldgen,
│                   #   datamaps, trades, advancements, guides)
├── parsers/        # nativeParsers, modParsers/, shared/, retention.ts, modVersion.ts
├── jar/ bytecode/ decompiler/ metadata/   # Primary evidence: JAR/JiJ, bytecode, decompile, metadata
├── client/         # McHttpClient (discovery, typed errors, metadata cache)
├── tools/          # The 11 agent-facing tools
├── transports/     # Streamable HTTP and stdio
├── server.ts       # Server assembly (version read from package.json via version.ts)
├── version.ts      # The package version, read where it is declared
└── index.ts        # CLI entrypoint and argument parsing
```

Contract mirror:

- `api/endpoints.ts` mirrors the mod's endpoint table: path, accepted parameters, page bounds, and a
  typed response per id. Tools name an endpoint; they never build a URL.
- `api/errors.ts` mirrors `InsightError`; `api/result.ts` defines `toFailure` and `MissingParts` so a
  mod failure keeps its code and message and a composite answer reports missing parts in the same shape
  the mod uses.
- `tests/contract.test.ts` reads `InsightEndpoint.java` and `InsightError.java` and fails when the
  client table drifts from the mod's declaration.

Data layer: each data kind is one module that calls its endpoint(s) and applies the same raw-JSON
retention rule and, where relevant, parsing. Nothing re-declares a response shape.

Parser tiers:

- **Native tier (`parsers/nativeParsers.ts`)** — mechanically explains Minecraft's own formats: vanilla
  recipes (including 1.21 components and compound ingredients), worldgen definitions, biome modifiers,
  loot tables, and Global Loot Modifiers. It does not parse registries or tags: registry data already
  arrives structured from the mod.
- **Built-in mod tier (`parsers/modParsers/`)** — built-in parsers for Create, Mekanism, AE2, Oritech,
  Farmer's Delight, Ars Nouveau, and TofuCraftReload. Each declares a `modVersionRange`; when the live
  mod version is outside it (or the loaded version map is unavailable), the parser refuses to guess and
  the record is reported as raw JSON with `parserVersionMismatch`.
- **One retention rule (`parsers/retention.ts`)** — `rawJson` is kept when no parser ran, when the
  parser reported `hasUnparsedFields`, or when the caller asked (`includeRaw`). Otherwise the parsed
  result is the answer. `parsers/shared.ts` and `unparsedRecipeFlag` give both tiers the same way to
  report unexplained input, so "unparsed data is kept verbatim" is a property of the parser layer.
- **Primary evidence (`jar/`, `bytecode/`, `decompiler/`, `metadata/`)** — JAR and Jar-in-Jar indexing,
  JAR/pack-script search, `<clinit>`/`ConstantValue` ID tracing, Vineflower 1.12.0 or `javap`
  decompilation (Vineflower is downloaded into a tools directory and verified by SHA-256), and JVM
  class reflection through the mod's `introspect_class`.

The 12 tools (names unchanged):

| Tool | Purpose |
|---|---|
| `mc_status` | Connection health, world state, per-viewer availability map, integrations, loaded mod list (`filteredModCount` when a client-side mod filter is applied) |
| `mc_player_state` | Player position, dimension, biome, environment, vitals, effects, inventory, open screen |
| `mc_observe_world` | `mode=looking_at` raycast target, or `mode=area` scan of blocks, block entities, and entities |
| `mc_search_registry` | Resolve an item/block/fluid/entity/tag id by id or localized display name; the resolution step before any exact `item=`/`id=` argument |
| `mc_inspect` | Registry entry metadata (`defaultComponents`, `foodProperties`, `blockInfo`, `viewerInformation`) or a tag's members |
| `mc_query_item_flow` | Cross-query recipes (input/output/catalyst roles), loot, trades, data maps, and registry metadata for one exact id; unreadable parts appear under `missing` with `partial: true` |
| `mc_query_recipes` | List recipe types/viewer categories/built-in parsers (`listTypes=true`), or query recipes by exact item, role, type, mod, and source |
| `mc_render_recipe` | Draw the recipes that make an exact item, as the viewer draws them, and return one MCP image block per recipe beside the same answer as text |
| `mc_query_world_data` | Worldgen kinds (with biome temperature filters), loot, trades, and NeoForge data maps |
| `mc_query_advancements` | Advancement definitions, triggers, rewards, and live progress when the world owner allows it |
| `mc_guides` | Search, list, or read Patchouli/GuideME/FTB Quests/Ponder/Oracle/lang documents |
| `mc_inspect_code` | ID tracing, JAR/pack-script search, resource reading, decompilation, and JVM class reflection |

Tool arguments are MCP-facing names (`query`, `modId`, `target`, `direction`, …); each data module maps
them to the endpoint's parameter names (`q`, `mod`, `id`, …). A tool result is always a JSON object
with `ok`; failures are `{ok: false, code, error}` with the mod's code when the mod answered.
`mc_render_recipe` is the one tool whose result also carries MCP image blocks: the text block keeps
the same JSON envelope (with the picture bytes removed), and each image block is the same answer
drawn. A client that does not render images therefore loses nothing but the picture.

---

## 10. Configuration and Limits

`config/mcinsight.json` (created on first launch):

| Key | Default | Hard maximum / notes |
|---|---|---|
| `enabled` | `true` | Whether the loopback HTTP server starts |
| `port` | `25580` | Bind start port, valid range `1024..65535` |
| `portRangeSize` | `20` | Consecutive ports tried, valid range `1..50` |
| `authToken` | `""` | Required Bearer token when non-empty |
| `maxScanRadius` | `16` | Scan radius cap; hard maximum `32` (`HARD_MAX_SCAN_RADIUS`) |
| `maxSyncBlockRadius` | `16` | Block-entity sync radius cap over the bridge; hard maximum `16` (`HARD_MAX_SYNC_BLOCK_RADIUS`) |
| `maxRecipeResults` | `100` | Lowers `recipes_query`'s effective page ceiling; hard maximum `500` (`HARD_MAX_RECIPE_RESULTS`) |

The three caps are resolved once in `config/Limits` and read through accessors
(`Limits.scanRadius()`, `Limits.syncBlockRadius()`, `Limits.recipeResults()`), so they are operator
settings rather than compiled constants and apply wherever the data is served, including the server
side of a bridged request. Out-of-range values are clamped (with a log line) rather than rejected.
The raycast ceiling is the exception: no config key exists and `Limits.raycastDistance()` is always
`HARD_MAX_RAYCAST_DISTANCE` (64).

MCP server flags and environment variables: `--stdio`/`--http`, `--port`/`MCP_PORT` (default `3876`),
`--mc-url`/`MCINSIGHT_URL`, `--token`/`MCINSIGHT_TOKEN`, `--mods-dir`/`MCINSIGHT_MODS_DIR`,
`--game-dir`/`MCINSIGHT_GAME_DIR`, `--tools-dir`/`MCINSIGHT_TOOLS_DIR`. HTTP mode validates `Host` and
`Origin` as localhost and serves `/mcp` plus `/health`.

---

## 11. Benchmark Harness (`bench/`)

- `setup-perf-env.mjs` generates a synthetic perf datapack (5000 recipes, 150 loot tables), 80 FTB
  Quests chapters, and 60 fixture JARs under `mod/neoforge/runs/client` / `bench/fixtures/mods`.
- `run-perf-bench.mjs` measures per-endpoint cold and warm latency plus main-thread microseconds from
  the diagnostic headers, a parallel stress pass (concurrency 8, 160 requests), and MCP-server E2E
  latency plus JAR-index and port-discovery timings. The measurement approach is unchanged from one
  run to the next; only the measured URL list is data.
- The harness declares its URLs once (`BENCH_ENDPOINTS`, `STRESS_MIX`) and, before the first request,
  parses `InsightEndpoint.java` and checks each URL: the path must resolve to an HTTP endpoint, every
  query parameter must be in that endpoint's `allowedParams()`, and `limit`/`offset` must be inside the
  declared bounds. A violation aborts the run with every offending URL listed, because the mod would
  answer `400 INVALID_PARAMETER` and the harness would otherwise fail mid-run with one opaque error.
- The bench probes the same default range as the MCP client (`25580..25599`); a client bound elsewhere
  must be named with `--mc-url`.
- The MCP E2E portion imports `mcp-server/dist`, so the server must be built before benchmarking.
- `bench/results/` and `bench/fixtures/` are git-ignored, so a report is local to the run.

---

## 12. End-to-End Tests in Real Minecraft (`mcp-server/e2e/`)

The e2e runner starts real Minecraft from the loaders' dev runtimes and drives it through the MCP
server over Streamable HTTP, the way an agent would.

- **Launch.** Each loader has `e2eClient`/`e2eServer` runs (NeoForge: MDG runs; Fabric: Loom runs)
  on an `e2e` source set. `exportE2eClientLaunch`/`exportE2eServerLaunch` write
  `mod/<loader>/versions/<mc_version>/build/e2e/{client,server}.json`: the Java executable, JVM and
  program arguments, and
  two alternative argument sets, `withMod` and `withoutMod`. The runner launches the JVM from that spec
  itself, so it owns the process, captures its log, and stops it. "Without McInsight" is the same game
  without the mod: NeoForge leaves it out of `fml.modFolders`, Fabric disables it with
  `fabric.debug.disableModIds`.
- **Harness mod.** The `e2e` source set is a separate test-only mod, `mcinsight_e2e`, never packaged.
  It logs the markers the runner waits for (`[E2E] server ready`, `[E2E] player ready`), creates a
  creative flat world with a fixed seed when the client is started with
  `-Dmcinsight.e2e.createWorld=<name>` (quick play can only open an existing world), and, when a player
  joins, runs `e2e-setup.mcfunction` from the game directory as that player. The setup places the
  blocks, block entities, entity and items the checks read, so every loader sees the same world. A
  client that enters a world captures the mouse and covers whatever is behind it, so the harness
  hands the cursor back on the tick after each capture and sends the window to the taskbar once,
  when the runner asks (`-Dmcinsight.e2e.minimizeWindow=true`, which it passes wherever a real
  display is in use: a headless CI client keeps its window on the virtual display, where minimizing
  would test nothing).
- **Scenarios.** `singleplayer` (the client hosts the integrated server), `multiplayer-mod` (a
  dedicated server running McInsight; server data travels over the packet bridge) and
  `multiplayer-nomod` (a dedicated server without McInsight; server data must fail as
  `SERVER_MOD_REQUIRED` and composite answers must say what is missing). Each scenario runs in a fresh
  directory under `mod/<loader>/versions/<mc_version>/runs/e2e/<loader>-<scenario>/`, and its process
  logs stay there.
- **Scheduling.** Scenarios share nothing but the machine — each owns its game directory, its ports and
  its processes — so the runner runs up to `--jobs` of them at once. Without `--jobs` it runs one per
  four hardware threads, at most four, which leaves a four-core CI runner at one, as before the option
  existed. Jobs print with a `<version>/<loader>/<scenario>` prefix and each is timed, and the closing
  summary reports the whole run's wall time, so a parallel run stays readable.
- **Stopping.** A client cannot be told to stop: it ignores console input, and waiting for it was a
  fixed 30 s of nothing after every scenario. The runner closes its window instead (a `WM_CLOSE` on
  Windows, `SIGTERM` elsewhere), which is how the game saves and exits; a dedicated server gets `stop`
  and 30 s to save. Either side is killed when it outlives its deadline.
- **Suites and presets.** Checks are grouped into suites (`status`, `registry`, `recipes`, `world`,
  `worlddata`, `progress`, `guides`, `contract`, `bridge`, `nomod`). Each scenario lists the suites
  that apply to it, and `--suite` narrows that. Presets name common selections: `quick` (NeoForge
  singleplayer, the default), `multiplayer` (both NeoForge multiplayer scenarios), and `release`
  (every Minecraft version, loader and scenario). `--version`, `--loader` and `--list` narrow or print them.
- **Mod set.** By default the runs load only McInsight, the harness, and JEI (the viewer the real game
  would have), so a run is deterministic. `--extra-mods <dir>` adds every jar in that directory to the
  e2e runs for a local experiment; it is refused under `CI`. A run whose extra mods replace the
  viewer sets `MCINSIGHT_E2E_VIEWERS` (comma-separated) so the startup wait expects what it loaded.
- **JEI version.** Both loaders' e2e runs load the JEI build the mod compiles against (`jei` in
  `mod/stonecutter.properties.toml`), so the bridge is exercised against the API it was written for.
- **CI.** `.github/workflows/e2e.yml` runs the `quick` preset on pull requests and pushes. A
  `workflow_dispatch` can choose any preset, and `release.yml` calls it with `release` before
  publishing. On Linux, `MCINSIGHT_E2E_XVFB=1` makes the runner start the client under `xvfb-run` with
  Mesa software rendering: OpenGL comes from `llvmpipe`, and `26.x` reaches the game through the Vulkan
  backend on `lavapipe`, because the runner's X server offers no GLX visual RenderPearl's OpenGL
  backend accepts. The client's own `latest.log` records the backend and driver it used. Game and MCP
  logs are uploaded as an artifact.

```bash
cd mcp-server
node e2e/run.mjs                        # quick
node e2e/run.mjs --preset release       # everything
node e2e/run.mjs --preset release --jobs 2   # everything, two scenarios at a time
node e2e/run.mjs --loader fabric --scenario multiplayer-nomod
node e2e/run.mjs --scenario singleplayer --suite recipes,worlddata --skip-build
node e2e/run.mjs --extra-mods ~/my-mods # local only
```

---

## 13. Explicitly Not Verified Here

- Which dynamic registries a bare multiplayer client receives is a Minecraft property, not a McInsight
  contract. This document therefore does not claim that every `SERVER_DATA` kind is unreadable on a
  client connected to a server without McInsight; it states the routing rule and the codes each
  collector actually throws (section 2.2).
- There is no compatibility shim for the older response shapes. This is a beta-stage breaking change:
  endpoints, parameters, page fields, error codes, and viewer metadata changed together, and old
  clients/tools must be updated rather than tolerated.

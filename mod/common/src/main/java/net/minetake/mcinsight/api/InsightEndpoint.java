package net.minetake.mcinsight.api;

import java.util.Set;

/**
 * The single endpoint table.
 *
 * <p>Every transport (loopback HTTP and the multiplayer packet bridge) resolves requests through
 * this enum, so endpoint names, accepted parameters, page bounds, and the rule about which data
 * needs the server are declared exactly once. A parameter that is not listed here is rejected
 * instead of reaching a handler that would ignore it.
 */
public enum InsightEndpoint {

    STATUS("status", "/api/v1/status", DataScope.LOCAL, PlayerScope.LOCAL, 5_000, Set.of("includeMods")),
    MODS("mods", "/api/v1/mods", DataScope.LOCAL, PlayerScope.NONE, 5_000, Set.of()),
    METRICS("metrics", "/api/v1/metrics", DataScope.LOCAL, PlayerScope.NONE, 5_000, Set.of()),
    PLAYER("player", "/api/v1/player", DataScope.COMPOSITE, PlayerScope.LOCAL, 5_000, Set.of()),

    LOOKING_AT("looking_at", "/api/v1/looking-at", DataScope.COMPOSITE, PlayerScope.LOCAL, 5_000,
        Set.of("maxDistance", "includeFluids")),
    SCAN("scan", "/api/v1/scan", DataScope.COMPOSITE, PlayerScope.LOCAL, 5_000,
        Set.of("radius", "filter")),

    REGISTRY_SEARCH("registry_search", "/api/v1/registry/search", DataScope.LOCAL, PlayerScope.NONE, 5_000,
        Set.of("kind", "q", "mod", "limit", "offset"), 50, 200),
    REGISTRY_INSPECT("registry_inspect", "/api/v1/registry/inspect", DataScope.LOCAL, PlayerScope.NONE, 5_000,
        Set.of("kind", "id")),
    REGISTRY_TAG("registry_tag", "/api/v1/registry/tag", DataScope.LOCAL, PlayerScope.NONE, 5_000,
        Set.of("kind", "id", "limit", "offset"), 200, 500),

    RECIPES_TYPES("recipes_types", "/api/v1/recipes/types", DataScope.COMPOSITE, PlayerScope.NONE, 10_000, Set.of()),
    RECIPES_QUERY("recipes_query", "/api/v1/recipes/query", DataScope.COMPOSITE, PlayerScope.NONE, 10_000,
        Set.of("item", "role", "type", "mod", "source", "limit", "offset"), 40, 100),
    RECIPES_RENDER("recipes_render", "/api/v1/recipes/render", DataScope.LOCAL, PlayerScope.NONE, 20_000,
        Set.of("item", "recipeId", "role", "type", "source", "limit", "offset"), 1, 8),

    GUIDES_LIST("guides_list", "/api/v1/guides/list", DataScope.LOCAL, PlayerScope.NONE, 5_000,
        Set.of("source", "mod", "limit", "offset"), 50, 500),
    GUIDES_SEARCH("guides_search", "/api/v1/guides/search", DataScope.LOCAL, PlayerScope.NONE, 5_000,
        Set.of("q", "source", "mod", "lang", "includeAllLang", "limit", "offset"), 20, 50),
    GUIDES_PAGE("guides_page", "/api/v1/guides/page", DataScope.LOCAL, PlayerScope.NONE, 5_000, Set.of("id")),

    WORLDGEN_SUMMARY("worldgen_summary", "/api/v1/worldgen/summary", DataScope.SERVER_DATA, PlayerScope.NONE, 10_000,
        Set.of()),
    WORLDGEN_QUERY("worldgen_query", "/api/v1/worldgen/query", DataScope.SERVER_DATA, PlayerScope.NONE, 10_000,
        Set.of("kind", "q", "mod", "searchContent", "minTemp", "maxTemp", "summaryOnly", "limit", "offset"), 30, 100),
    LOOT_QUERY("loot_query", "/api/v1/loot/query", DataScope.SERVER_DATA, PlayerScope.NONE, 10_000,
        Set.of("q", "item", "mod", "includeModifiers", "limit", "offset"), 30, 100),
    DATAMAPS_QUERY("datamaps_query", "/api/v1/datamaps/query", DataScope.SERVER_DATA, PlayerScope.NONE, 10_000,
        Set.of("q", "item", "registry", "mod", "includeRaw", "limit", "offset"), 30, 100),
    TRADES_QUERY("trades_query", "/api/v1/trades/query", DataScope.SERVER_DATA, PlayerScope.NONE, 10_000,
        Set.of("item", "side", "q", "profession", "mod", "limit", "offset"), 40, 200),
    ADVANCEMENTS_QUERY("advancements_query", "/api/v1/advancements/query", DataScope.SERVER_DATA, PlayerScope.REQUESTER,
        8_000, Set.of("q", "mod", "status", "includeRecipes", "limit", "offset"), 40, 200),

    INTROSPECT_CLASS("introspect_class", "/api/v1/introspect/class", DataScope.LOCAL, PlayerScope.NONE, 5_000,
        Set.of("name")),

    /** Composite parts fetched from a remote server; never exposed over HTTP. */
    SERVER_LOOKING_AT_BLOCK_ENTITY("server_looking_at_block_entity", null, DataScope.SERVER_DATA,
        PlayerScope.REQUESTER, 5_000, Set.of("x", "y", "z")),
    SERVER_SCAN_BLOCK_ENTITIES("server_scan_block_entities", null, DataScope.SERVER_DATA,
        PlayerScope.REQUESTER, 5_000, Set.of("radius", "filter")),
    SERVER_RECIPES_TYPES("server_recipes_types", null, DataScope.SERVER_DATA, PlayerScope.NONE, 10_000, Set.of()),
    SERVER_RECIPES_QUERY("server_recipes_query", null, DataScope.SERVER_DATA, PlayerScope.NONE, 10_000,
        Set.of("item", "role", "type", "mod", "source", "limit", "offset"), 40, 100),
    SERVER_PLAYER_ENDER_CHEST("server_player_ender_chest", null, DataScope.SERVER_DATA, PlayerScope.REQUESTER, 5_000,
        Set.of());

    /** Where the authoritative values for an endpoint live. */
    public enum DataScope {
        /** Answerable from the client alone. */
        LOCAL,
        /** Only the world owner (integrated server or a remote server running McInsight) has the data. */
        SERVER_DATA,
        /** Built from local data plus optional server-owned parts; missing parts are reported. */
        COMPOSITE
    }

    /** Which player a query is scoped to. */
    public enum PlayerScope {
        NONE,
        /** The player at this client. */
        LOCAL,
        /** The player who issued the request over the packet bridge. */
        REQUESTER
    }

    private final String id;
    private final String httpPath;
    private final DataScope dataScope;
    private final PlayerScope playerScope;
    private final long budgetMs;
    private final Set<String> allowedParams;
    private final int defaultLimit;
    private final int maxLimit;

    InsightEndpoint(
        String id,
        String httpPath,
        DataScope dataScope,
        PlayerScope playerScope,
        long budgetMs,
        Set<String> allowedParams
    ) {
        this(id, httpPath, dataScope, playerScope, budgetMs, allowedParams, 0, 0);
    }

    InsightEndpoint(
        String id,
        String httpPath,
        DataScope dataScope,
        PlayerScope playerScope,
        long budgetMs,
        Set<String> allowedParams,
        int defaultLimit,
        int maxLimit
    ) {
        this.id = id;
        this.httpPath = httpPath;
        this.dataScope = dataScope;
        this.playerScope = playerScope;
        this.budgetMs = budgetMs;
        this.allowedParams = allowedParams;
        this.defaultLimit = defaultLimit;
        this.maxLimit = maxLimit;
    }

    public String id() {
        return id;
    }

    public String httpPath() {
        return httpPath;
    }

    public DataScope dataScope() {
        return dataScope;
    }

    public PlayerScope playerScope() {
        return playerScope;
    }

    public long budgetMs() {
        return budgetMs;
    }

    public Set<String> allowedParams() {
        return allowedParams;
    }

    public boolean paginated() {
        return maxLimit > 0;
    }

    public int defaultLimit() {
        return defaultLimit;
    }

    public int maxLimit() {
        return maxLimit;
    }

    /**
     * Reads the page size declared by this endpoint. Bounds are enforced here, so a caller asking
     * for more than the endpoint can serve gets an error instead of a silently shortened page.
     *
     * <p>An operator cap can lower the ceiling (recipes are the expensive case); it can never raise
     * it above what the endpoint was designed to return.
     */
    public int limit(Params params) {
        if (!paginated()) {
            throw new IllegalStateException("Endpoint " + id + " is not paginated");
        }
        int max = effectiveMaxLimit();
        return params.limit(Math.min(defaultLimit, max), max);
    }

    /** The maximum this endpoint will serve in the current session. */
    public int effectiveMaxLimit() {
        if (this == RECIPES_QUERY) {
            return Math.min(maxLimit, net.minetake.mcinsight.config.Limits.recipeResults());
        }
        return maxLimit;
    }

    public static InsightEndpoint fromId(String id) {
        if (id == null) {
            return null;
        }
        String needle = id.trim();
        for (InsightEndpoint endpoint : values()) {
            if (endpoint.id.equals(needle)) {
                return endpoint;
            }
        }
        return null;
    }

    public static InsightEndpoint fromHttpPath(String path) {
        if (path == null) {
            return null;
        }
        for (InsightEndpoint endpoint : values()) {
            if (endpoint.httpPath != null && endpoint.httpPath.equals(path)) {
                return endpoint;
            }
        }
        return null;
    }
}

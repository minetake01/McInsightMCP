package net.minetake.mcinsight.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * The contract the whole mod hangs on: one endpoint table, one error catalogue, one page shape.
 */
class ContractTest {

    @Test
    void everyHttpPathIsUniqueAndMatchesTheV1Prefix() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (InsightEndpoint endpoint : InsightEndpoint.values()) {
            String path = endpoint.httpPath();
            if (path == null) {
                continue;
            }
            assertTrue(path.startsWith("/api/v1/"), "path must be under /api/v1: " + path);
            assertTrue(seen.add(path), "duplicate path: " + path);
            assertEquals(endpoint, InsightEndpoint.fromHttpPath(path));
        }
    }

    @Test
    void bridgedServerPartsAreNotReachableOverHttp() {
        assertNull(InsightEndpoint.SERVER_LOOKING_AT_BLOCK_ENTITY.httpPath());
        assertNull(InsightEndpoint.SERVER_SCAN_BLOCK_ENTITIES.httpPath());
        assertNull(InsightEndpoint.fromHttpPath(null));
        assertNull(InsightEndpoint.fromId("no_such_endpoint"));
    }

    @Test
    void paginatedEndpointsDeclareConsistentBounds() {
        for (InsightEndpoint endpoint : InsightEndpoint.values()) {
            boolean declaresLimit = endpoint.allowedParams().contains("limit");
            assertEquals(endpoint.paginated(), declaresLimit, endpoint.id() + " limit/parameter mismatch");
            if (!endpoint.paginated()) {
                continue;
            }
            assertTrue(endpoint.defaultLimit() >= 1, endpoint.id() + " default limit");
            assertTrue(endpoint.maxLimit() >= endpoint.defaultLimit(), endpoint.id() + " max below default");
            assertTrue(endpoint.allowedParams().contains("offset"), endpoint.id() + " paginated without offset");
        }
    }

    @Test
    void serverDataEndpointsAreTheOnesTheBridgeMustServe() {
        assertEquals(InsightEndpoint.DataScope.SERVER_DATA, InsightEndpoint.LOOT_QUERY.dataScope());
        assertEquals(InsightEndpoint.DataScope.SERVER_DATA, InsightEndpoint.WORLDGEN_QUERY.dataScope());
        assertEquals(InsightEndpoint.DataScope.SERVER_DATA, InsightEndpoint.DATAMAPS_QUERY.dataScope());
        assertEquals(InsightEndpoint.DataScope.SERVER_DATA, InsightEndpoint.ADVANCEMENTS_QUERY.dataScope());
        assertTrue(InsightDispatcher.exposedToRemoteClients(InsightEndpoint.LOOT_QUERY));
        assertFalse(InsightDispatcher.exposedToRemoteClients(InsightEndpoint.PLAYER));
        assertFalse(InsightDispatcher.exposedToRemoteClients(InsightEndpoint.RECIPES_QUERY));
    }

    @Test
    void everyErrorCodeDeclaresAnHttpStatus() {
        for (InsightError error : InsightError.values()) {
            int status = error.httpStatus();
            assertTrue(status >= 400 && status < 600, error.name() + " must be an error status, got " + status);
            assertFalse(error.defaultMessage().isBlank(), error.name() + " must describe itself");
        }
        assertEquals(404, InsightError.NOT_FOUND.httpStatus());
        assertEquals(429, InsightError.RATE_LIMITED.httpStatus());
        assertEquals(503, InsightError.SERVER_MOD_REQUIRED.httpStatus());
        assertEquals(504, InsightError.REMOTE_TIMEOUT.httpStatus());
    }

    @Test
    void exceptionsCarryTheirCatalogueEntry() {
        InsightException withMessage = new InsightException(InsightError.OUT_OF_RANGE, "too far");
        assertEquals(InsightError.OUT_OF_RANGE, withMessage.error());
        assertEquals("too far", withMessage.getMessage());

        InsightException withoutMessage = new InsightException(InsightError.VIEWER_UNAVAILABLE);
        assertEquals(InsightError.VIEWER_UNAVAILABLE.defaultMessage(), withoutMessage.getMessage());
    }

    @Test
    void pageShapeIsIdenticalWhetherOrNotTheTotalWasCounted() {
        JsonArray items = new JsonArray();
        JsonObject counted = Page.of("entries", items, 10, 30);
        assertEquals(true, counted.get("totalKnown").getAsBoolean());
        assertEquals(30, counted.get("total").getAsInt());
        assertEquals(0, counted.get("returned").getAsInt());
        assertEquals(10, counted.get("offset").getAsInt());
        assertTrue(counted.keySet().containsAll(java.util.List.of("ok", "offset", "returned", "hasMore")));

        JsonArray page = new JsonArray();
        page.add(new JsonObject());
        JsonObject uncounted = Page.uncounted("recipes", page, 5, true);
        assertEquals(false, uncounted.get("totalKnown").getAsBoolean());
        assertFalse(uncounted.has("total"));
        assertEquals(1, uncounted.get("returned").getAsInt());
        assertEquals(true, uncounted.get("hasMore").getAsBoolean());

        JsonObject full = Page.of("entries", page, 0, 1);
        assertEquals(false, full.get("hasMore").getAsBoolean());
    }

    @Test
    void partialAnswersNameTheMissingPartAndItsCode() {
        PartialData partial = new PartialData();
        assertTrue(partial.isEmpty());
        partial.add("server_scan_block_entities", InsightError.SERVER_MOD_REQUIRED, null);
        assertFalse(partial.isEmpty());

        JsonObject out = new JsonObject();
        partial.applyTo(out);
        assertEquals(true, out.get("partial").getAsBoolean());
        JsonObject missing = out.getAsJsonArray("missing").get(0).getAsJsonObject();
        assertEquals("server_scan_block_entities", missing.get("part").getAsString());
        assertEquals("SERVER_MOD_REQUIRED", missing.get("code").getAsString());
        assertFalse(missing.get("error").getAsString().isBlank());
    }

    @Test
    void itemQueryAcceptsOnlyFullIdsAndTags() {
        ItemQuery exact = ItemQuery.parse("minecraft:stone");
        assertEquals("minecraft:stone", exact.id());
        assertFalse(exact.isTag());

        ItemQuery tag = ItemQuery.parse("#minecraft:planks");
        assertTrue(tag.isTag());
        assertEquals("minecraft:planks", tag.tagId());

        assertThrows(InsightException.class, () -> ItemQuery.parse("stone"));
        assertThrows(InsightException.class, () -> ItemQuery.parse(":broken"));
        assertNull(ItemQuery.optional(null));
        assertNull(ItemQuery.optional("  "));
    }
}

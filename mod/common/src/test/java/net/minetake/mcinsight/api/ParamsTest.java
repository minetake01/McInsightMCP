package net.minetake.mcinsight.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ParamsTest {

    @Test
    void decodesUrlEncodedQueryParameters() {
        Params params = Params.fromQueryString("q=iron%20ingot&tag=%23minecraft%3Aplanks&flag=true&empty=&bare");
        assertEquals("iron ingot", params.string("q"));
        assertEquals("#minecraft:planks", params.string("tag"));
        assertTrue(params.bool("flag", false));
        assertNull(params.string("empty"));
        assertNull(params.string("bare"));
    }

    @Test
    void handlesNullAndBlankQueries() {
        assertTrue(Params.fromQueryString(null).keys().isEmpty());
        assertTrue(Params.fromQueryString("   ").keys().isEmpty());
    }

    @Test
    void failsFastOnMalformedUrlEncoding() {
        InsightException error = assertThrows(InsightException.class, () -> Params.fromQueryString("q=%ZZ"));
        assertEquals(InsightError.INVALID_QUERY, error.error());
    }

    @Test
    void parsesIntsOrThrowsInvalidParameter() {
        assertEquals(42, Params.of(java.util.Map.of("limit", "42")).intOr("limit", 10));
        assertEquals(10, Params.of(java.util.Map.of("limit", "  ")).intOr("limit", 10));
        InsightException error = assertThrows(InsightException.class, () ->
            Params.of(java.util.Map.of("limit", "12.5")).intOr("limit", 10)
        );
        assertEquals(InsightError.INVALID_PARAMETER, error.error());
    }

    @Test
    void rejectsLimitsOutsideTheDeclaredBounds() {
        assertEquals(40, Params.of(java.util.Map.of()).limit(40, 100));
        assertEquals(100, Params.of(java.util.Map.of("limit", "100")).limit(40, 100));
        InsightException tooLarge = assertThrows(InsightException.class, () ->
            Params.of(java.util.Map.of("limit", "101")).limit(40, 100)
        );
        assertEquals(InsightError.INVALID_PARAMETER, tooLarge.error());
        assertThrows(InsightException.class, () -> Params.of(java.util.Map.of("offset", "-1")).offset());
    }

    @Test
    void parsesFiniteDoublesAndRejectsNanOrInfinity() {
        assertEquals(1.5, Params.of(java.util.Map.of("maxDistance", "1.5")).doubleOr("maxDistance", 0.0), 1e-9);
        assertNull(Params.of(java.util.Map.of()).nullableDouble("minTemp"));
        assertEquals(-0.25, Params.of(java.util.Map.of("minTemp", " -0.25 ")).nullableDouble("minTemp"), 1e-9);

        assertThrows(InsightException.class, () ->
            Params.of(java.util.Map.of("maxDistance", "NaN")).doubleOr("maxDistance", 1.0));
        assertThrows(InsightException.class, () ->
            Params.of(java.util.Map.of("maxTemp", "Infinity")).nullableDouble("maxTemp"));
        assertThrows(InsightException.class, () ->
            Params.of(java.util.Map.of("maxDistance", "abc")).doubleOr("maxDistance", 1.0));
    }

    @Test
    void parsesStrictBooleansOnly() {
        assertTrue(Params.of(java.util.Map.of("includeRaw", "TRUE")).bool("includeRaw", false));
        assertEquals(false, Params.of(java.util.Map.of("includeRaw", "false")).bool("includeRaw", true));
        assertTrue(Params.of(java.util.Map.of()).bool("includeRaw", true));

        assertThrows(InsightException.class, () ->
            Params.of(java.util.Map.of("includeRaw", "yes")).bool("includeRaw", false));
        assertThrows(InsightException.class, () ->
            Params.of(java.util.Map.of("includeRaw", "1")).bool("includeRaw", false));
    }

    @Test
    void rejectsUnknownParameters() {
        InsightException error = assertThrows(InsightException.class, () ->
            Params.of(java.util.Map.of("unknownParam", "x")).rejectUnknownKeys(java.util.Set.of("q"))
        );
        assertEquals(InsightError.INVALID_PARAMETER, error.error());
    }

    @Test
    void parsesJsonObjectParametersForThePacketTransport() {
        Params params = Params.fromJsonText("{\"limit\":5,\"includeRaw\":true}");
        assertEquals(5, params.intOr("limit", 0));
        assertTrue(params.bool("includeRaw", false));
        assertThrows(InsightException.class, () -> Params.fromJsonText("not json"));
        assertThrows(InsightException.class, () -> Params.fromJsonText("[1,2]"));
    }
}

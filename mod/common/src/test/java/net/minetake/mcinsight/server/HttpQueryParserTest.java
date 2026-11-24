package net.minetake.mcinsight.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpQueryParserTest {

    @Test
    void decodesUrlEncodedQueryParameters() {
        Map<String, String> map = HttpQueryParser.parseQuery("q=iron%20ingot&tag=%23minecraft%3Aplanks&flag=true&empty=&bare");
        assertEquals("iron ingot", map.get("q"));
        assertEquals("#minecraft:planks", map.get("tag"));
        assertEquals("true", map.get("flag"));
        assertEquals("", map.get("empty"));
        assertEquals("", map.get("bare"));
    }

    @Test
    void handlesNullAndBlankQueries() {
        assertTrue(HttpQueryParser.parseQuery(null).isEmpty());
        assertTrue(HttpQueryParser.parseQuery("   ").isEmpty());
    }

    @Test
    void failsFastOnMalformedUrlEncoding() {
        var ex = assertThrows(HttpQueryParser.QueryParseException.class, () ->
            HttpQueryParser.parseQuery("q=%ZZ")
        );
        assertEquals("INVALID_QUERY", ex.getCode());
    }

    @Test
    void parsesIntsOrThrowsInvalidParameter() {
        assertEquals(42, HttpQueryParser.parseInt("42", 10, "limit"));
        assertEquals(10, HttpQueryParser.parseInt("  ", 10, "limit"));
        var ex = assertThrows(HttpQueryParser.QueryParseException.class, () ->
            HttpQueryParser.parseInt("12.5", 10, "limit")
        );
        assertEquals("INVALID_PARAMETER", ex.getCode());
    }

    @Test
    void parsesFiniteDoublesAndRejectsNanOrInfinity() {
        assertEquals(1.5, HttpQueryParser.parseDouble("1.5", 0.0, "maxDistance"), 1e-9);
        assertNull(HttpQueryParser.parseNullableDouble(null, "minTemp"));
        assertEquals(-0.25, HttpQueryParser.parseNullableDouble(" -0.25 ", "minTemp"), 1e-9);

        assertThrows(HttpQueryParser.QueryParseException.class, () ->
            HttpQueryParser.parseDouble("NaN", 1.0, "maxDistance")
        );
        assertThrows(HttpQueryParser.QueryParseException.class, () ->
            HttpQueryParser.parseNullableDouble("Infinity", "maxTemp")
        );
        assertThrows(HttpQueryParser.QueryParseException.class, () ->
            HttpQueryParser.parseDouble("abc", 1.0, "maxDistance")
        );
    }

    @Test
    void parsesStrictBooleansOnly() {
        assertTrue(HttpQueryParser.parseBoolean("TRUE", false, "includeRaw"));
        assertFalse(HttpQueryParser.parseBoolean("false", true, "includeRaw"));
        assertTrue(HttpQueryParser.parseBoolean("", true, "includeRaw"));

        assertThrows(HttpQueryParser.QueryParseException.class, () ->
            HttpQueryParser.parseBoolean("yes", false, "includeRaw")
        );
        assertThrows(HttpQueryParser.QueryParseException.class, () ->
            HttpQueryParser.parseBoolean("1", false, "includeRaw")
        );
    }
}

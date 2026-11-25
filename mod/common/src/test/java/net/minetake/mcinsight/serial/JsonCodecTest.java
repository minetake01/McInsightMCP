package net.minetake.mcinsight.serial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonCodecTest {

    @SuppressWarnings("unused")
    private static final class Node {
        String name;
        Node next;
        double weird;
        Node shared;
    }

    @Test
    void handlesCyclesSharedDagNodesAndNonFiniteNumbers() {
        Node leaf = new Node();
        leaf.name = "leaf";
        leaf.weird = Double.NaN;

        Node root = new Node();
        root.name = "root";
        root.weird = Double.POSITIVE_INFINITY;
        root.next = root;
        root.shared = leaf;

        JsonObject snap = JsonCodec.snapshotObject(root, 4).getAsJsonObject();
        assertEquals("root", snap.get("name").getAsString());
        assertEquals("Infinity", snap.get("weird").getAsString());
        assertTrue(snap.get("next").getAsString().startsWith("<cyclic:"));

        JsonObject sharedSnap = snap.getAsJsonObject("shared");
        assertEquals("leaf", sharedSnap.get("name").getAsString());
        assertEquals("NaN", sharedSnap.get("weird").getAsString());

        byte[] bytes = JsonCodec.toUtf8Bytes(snap);
        assertTrue(new String(bytes).contains("\"Infinity\""));
    }

    @Test
    void enforcesCollectionTruncationAndDepthLimits() {
        List<Integer> big = new ArrayList<>();
        for (int i = 0; i < JsonCodec.MAX_COLLECTION_ELEMENTS + 8; i++) {
            big.add(i);
        }
        JsonArray arr = JsonCodec.snapshotObject(big, 3).getAsJsonArray();
        assertEquals(JsonCodec.MAX_COLLECTION_ELEMENTS + 1, arr.size());
        assertEquals("<truncated:8_more>", arr.get(JsonCodec.MAX_COLLECTION_ELEMENTS).getAsString());

        Map<String, Object> nested = Map.of("a", Map.of("b", Map.of("c", 123)));
        JsonObject shallow = JsonCodec.snapshotObject(nested, 2).getAsJsonObject();
        assertTrue(shallow.getAsJsonObject("a").get("b").getAsString().startsWith("<maxDepth:"));
    }

    @Test
    void errorEnvelopeCarriesTheCatalogueCode() {
        JsonObject error = JsonCodec.errorObject(net.minetake.mcinsight.api.InsightError.NOT_FOUND, null);
        assertEquals(false, error.get("ok").getAsBoolean());
        assertEquals("NOT_FOUND", error.get("code").getAsString());
        assertEquals(
            net.minetake.mcinsight.api.InsightError.NOT_FOUND.defaultMessage(),
            error.get("error").getAsString()
        );
    }
}

package net.minetake.mcinsight.server;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonUtilTest {

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

        JsonObject snap = JsonUtil.snapshotObject(root, 4).getAsJsonObject();
        assertEquals("root", snap.get("name").getAsString());
        assertEquals("Infinity", snap.get("weird").getAsString());
        assertTrue(snap.get("next").getAsString().startsWith("<cyclic:"));

        JsonObject sharedSnap = snap.getAsJsonObject("shared");
        assertEquals("leaf", sharedSnap.get("name").getAsString());
        assertEquals("NaN", sharedSnap.get("weird").getAsString());

        // Verify valid UTF-8 serialization even with NaN/Infinity stringified
        byte[] bytes = JsonUtil.toUtf8Bytes(snap);
        assertTrue(new String(bytes).contains("\"Infinity\""));
    }

    @Test
    void enforcesCollectionTruncationAndDepthLimits() {
        List<Integer> big = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            big.add(i);
        }
        JsonArray arr = JsonUtil.snapshotObject(big, 3).getAsJsonArray();
        assertEquals(33, arr.size());
        assertEquals("<truncated:8_more>", arr.get(32).getAsString());

        Map<String, Object> nested = Map.of("a", Map.of("b", Map.of("c", 123)));
        JsonObject shallow = JsonUtil.snapshotObject(nested, 2).getAsJsonObject();
        assertTrue(shallow.getAsJsonObject("a").get("b").getAsString().startsWith("<maxDepth:"));
    }
}

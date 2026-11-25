package net.minetake.mcinsight.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** What {@code item=} selects: the item itself, or a slot declared to accept it, never a sibling. */
class ItemFilterTest {

    private static final Set<String> GEM_TAGS = Set.of("c:gems", "c:gems/emerald");

    @Test
    void anIdQueryMatchesThatItemOnly() {
        ItemFilter emerald = ItemFilter.forItem("minecraft:emerald", GEM_TAGS);

        assertTrue(emerald.matchesItem("minecraft:emerald", GEM_TAGS));
        // Lapis is also #c:gems; sharing a tag does not make it an emerald.
        assertFalse(emerald.matchesItem("minecraft:lapis_lazuli", Set.of("c:gems", "c:gems/lapis")));
    }

    @Test
    void anIdQueryMatchesASlotThatAcceptsTheItemsTag() {
        ItemFilter emerald = ItemFilter.forItem("minecraft:emerald", GEM_TAGS);

        assertTrue(emerald.matchesTagSlot("c:gems"));
        assertFalse(emerald.matchesTagSlot("minecraft:planks"));
    }

    @Test
    void aTagQueryMatchesEveryMemberAndTheTagItself() {
        ItemFilter planks = ItemFilter.forTag("minecraft:planks", Set.of("minecraft:oak_planks", "minecraft:birch_planks"));

        assertTrue(planks.matchesItem("minecraft:oak_planks", Set.of()));
        assertTrue(planks.matchesItem("modded:odd_planks", Set.of("minecraft:planks")));
        assertFalse(planks.matchesItem("minecraft:stick", Set.of("c:rods/wooden")));
        assertTrue(planks.matchesTagSlot("minecraft:planks"));
        assertFalse(planks.matchesTagSlot("minecraft:logs"));
    }
}

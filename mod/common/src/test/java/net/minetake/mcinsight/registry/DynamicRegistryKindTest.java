package net.minetake.mcinsight.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * A kind backed by a world's dynamic registries answers through the world's lookup, not through a
 * built-in registry. Outside a world that is "unavailable", which a caller can report, rather than
 * a failure on the registry the kind does not have.
 */
class DynamicRegistryKindTest {

    @Test
    void aDynamicKindIsUnavailableWithoutAWorld() {
        ResourceLocation id = ResourceLocation.parse("minecraft:is_forest");

        InsightException hasTag = assertThrows(
            InsightException.class,
            () -> RegistryIndex.hasTag(RegistryIndex.Kind.BIOME, id)
        );
        assertEquals(InsightError.DATA_UNAVAILABLE, hasTag.error());

        InsightException contains = assertThrows(
            InsightException.class,
            () -> RegistryIndex.contains(RegistryIndex.Kind.BIOME, id)
        );
        assertEquals(InsightError.DATA_UNAVAILABLE, contains.error());
    }
}

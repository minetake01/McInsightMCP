package net.minetake.mcinsight.api;

/**
 * Turns request parameters into the validated, typed selections collectors accept.
 *
 * <p>Keeping ID parsing here is what makes {@code item=} mean exactly one thing everywhere: a full
 * ResourceLocation, optionally tag-prefixed. Substring and display-name matching are deliberately
 * absent; callers resolve a name through the registry search endpoint first.
 */
public final class ItemQuery {

    private final String id;
    private final boolean tag;

    private ItemQuery(String id, boolean tag) {
        this.id = id;
        this.tag = tag;
    }

    public static ItemQuery parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        boolean isTag = trimmed.startsWith("#");
        String body = isTag ? trimmed.substring(1) : trimmed;
        if (body.indexOf(':') <= 0) {
            throw new InsightException(
                InsightError.INVALID_ID,
                "Parameter 'item' must be a full ID such as 'minecraft:stone' or a tag such as '#minecraft:planks', got: "
                    + raw + ". Use mc_search_registry to resolve a display name first."
            );
        }
        if (net.minecraft.resources.ResourceLocation.tryParse(body) == null) {
            throw new InsightException(
                InsightError.INVALID_ID,
                "Parameter 'item' is not a valid ResourceLocation, got: "
                    + raw + ". Use mc_search_registry to resolve a display name first."
            );
        }
        return new ItemQuery(body, isTag);
    }

    public static ItemQuery optional(String raw) {
        return raw == null || raw.trim().isEmpty() ? null : parse(raw);
    }

    public String id() {
        return id;
    }

    public boolean isTag() {
        return tag;
    }

    public String tagId() {
        return tag ? id : null;
    }

    /** Human readable form used in messages and logs. */
    @Override
    public String toString() {
        return tag ? "#" + id : id;
    }
}

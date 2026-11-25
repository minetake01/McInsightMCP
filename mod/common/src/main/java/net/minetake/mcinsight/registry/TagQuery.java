package net.minetake.mcinsight.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Page;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.game.Versioned;
import net.minetake.mcinsight.registry.RegistryIndex.Kind;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

/**
 * Answers {@code registry_tag}: the members of one tag.
 *
 * <p>A tag is small, so the total is counted rather than guessed, and the members are paged through
 * the same {@link Page} shape as every other listing. With {@code kind} omitted the tag is looked up
 * in every exposed registry, and the answer states which kinds declared it instead of silently
 * picking one and hiding the others.
 */
public final class TagQuery {

    private TagQuery() {}

    public static JsonObject tag(RequestContext ctx, Params p) throws Exception {
        int limit = InsightEndpoint.REGISTRY_TAG.limit(p);
        int offset = p.offset();
        String kindRaw = p.stringOr("kind", "auto");
        ResourceLocation tagId = parseTagId(p.required("id", InsightError.INVALID_ID));

        if ("auto".equalsIgnoreCase(kindRaw.trim())) {
            return inspectAuto(tagId, limit, offset);
        }
        return inspect(stripSuffix(kindRaw), tagId, limit, offset, null);
    }

    private static JsonObject inspectAuto(ResourceLocation tagId, int limit, int offset) {
        List<Kind> declaring = new ArrayList<>(Kind.values().length);
        for (Kind kind : Kind.values()) {
            if (RegistryIndex.hasTag(kind, tagId)) {
                declaring.add(kind);
            }
        }
        if (declaring.isEmpty()) {
            throw new InsightException(
                InsightError.NOT_FOUND,
                "No item, block, fluid, or entity tag is named #" + tagId + "."
            );
        }
        JsonArray declaredKinds = new JsonArray();
        for (Kind kind : declaring) {
            declaredKinds.add(kind.id() + "_tag");
        }
        return inspect(declaring.getFirst(), tagId, limit, offset, declaredKinds);
    }

    private static JsonObject inspect(
        Kind kind,
        ResourceLocation tagId,
        int limit,
        int offset,
        JsonArray alsoDeclaredBy
    ) {
        JsonObject out = switch (kind) {
            case ITEM -> members(kind, BuiltInRegistries.ITEM, tagId, limit, offset);
            case BLOCK -> members(kind, BuiltInRegistries.BLOCK, tagId, limit, offset);
            case FLUID -> members(kind, BuiltInRegistries.FLUID, tagId, limit, offset);
            case ENTITY -> members(kind, BuiltInRegistries.ENTITY_TYPE, tagId, limit, offset);
        };
        out.addProperty("kind", kind.id() + "_tag");
        out.addProperty("id", "#" + tagId);
        if (alsoDeclaredBy != null) {
            out.add("matchedTagKinds", alsoDeclaredBy);
        }
        return out;
    }

    private static <T> JsonObject members(
        Kind kind,
        Registry<T> registry,
        ResourceLocation tagId,
        int limit,
        int offset
    ) {
        TagKey<T> key = TagKey.create(registry.key(), tagId);
        Optional<HolderSet.Named<T>> declared = Versioned.tag(registry, key);
        if (declared.isEmpty()) {
            throw new InsightException(
                InsightError.NOT_FOUND,
                "No " + kind.id() + " tag is named #" + tagId + "."
            );
        }

        JsonArray rows = new JsonArray();
        int total = 0;
        for (Holder<T> holder : declared.get()) {
            ResourceLocation memberId = holder.unwrapKey().map(Versioned::idOf).orElse(null);
            if (memberId == null) {
                continue;
            }
            if (total >= offset && rows.size() < limit) {
                rows.add(rowOf(kind, memberId));
            }
            total++;
        }
        return Page.of("members", rows, offset, total);
    }

    private static JsonObject rowOf(Kind kind, ResourceLocation memberId) {
        JsonObject row = new JsonObject();
        row.addProperty("id", memberId.toString());
        row.addProperty("modId", memberId.getNamespace());
        String displayName = DisplayNames.of(kind, memberId);
        if (displayName != null) {
            row.addProperty("displayName", displayName);
        }
        return row;
    }

    private static ResourceLocation parseTagId(String raw) {
        String body = raw.trim();
        if (body.startsWith("#")) {
            body = body.substring(1);
        }
        ResourceLocation parsed = ResourceLocation.tryParse(body);
        if (parsed == null) {
            throw new InsightException(InsightError.INVALID_ID, "'id' is not a valid tag id: " + raw);
        }
        return parsed;
    }

    private static Kind stripSuffix(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("_tag")) {
            normalized = normalized.substring(0, normalized.length() - "_tag".length());
        }
        return Kind.parse(normalized);
    }
}

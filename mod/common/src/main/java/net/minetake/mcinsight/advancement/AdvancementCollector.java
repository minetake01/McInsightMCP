package net.minetake.mcinsight.advancement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.Page;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.Criterion;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;

/**
 * Answers {@code advancements_query}: advancement definitions and, when the world owner is
 * reachable, the requesting player's own completion progress.
 *
 * <p>Definitions and progress are captured together on the server thread, so completion is
 * reported against the same reload the definitions came from. Both belong to the world owner, which
 * is why the dispatcher routes this endpoint there; a server without this mod fails the request
 * rather than letting a definitions-only page pass for "nothing completed".
 */
public final class AdvancementCollector {

    private static final long CAPTURE_TIMEOUT_MS = 8_000L;
    private static final int SNAPSHOT_DEPTH = 2;

    private AdvancementCollector() {}

    /** One advancement, copied off the game thread together with the progress of one player. */
    private record Sample(
        ResourceLocation id,
        Advancement advancement,
        boolean done,
        float percent,
        List<String> completedCriteria,
        List<String> remainingCriteria
    ) {}

    public static JsonObject query(RequestContext ctx, Params p) throws Exception {
        int limit = InsightEndpoint.ADVANCEMENTS_QUERY.limit(p);
        int offset = p.offset();
        String query = lower(p.string("q"));
        String modId = lower(p.string("mod"));
        boolean includeRecipes = p.bool("includeRecipes", false);
        Status status = Status.parse(p.string("status"));

        List<Sample> samples = capture(ctx);

        JsonArray entries = new JsonArray();
        int total = 0;
        for (Sample sample : samples) {
            if (!includeRecipes && sample.id().getPath().startsWith("recipes/")) {
                continue;
            }
            if (!modId.isEmpty() && !sample.id().getNamespace().toLowerCase(Locale.ROOT).equals(modId)) {
                continue;
            }
            if (!status.accepts(sample)) {
                continue;
            }
            JsonObject entry = describe(sample);
            if (!query.isEmpty() && !matches(entry, query)) {
                continue;
            }
            if (total >= offset && entries.size() < limit) {
                entries.add(entry);
            }
            total++;
        }

        return Page.of("advancements", entries, offset, total);
    }

    /** The status filter, parsed once so both the query and the error message agree on its name. */
    private enum Status {
        ALL("all"),
        COMPLETED("completed"),
        INCOMPLETE("incomplete");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        static Status parse(String raw) {
            String normalized = lower(raw);
            if (normalized.isEmpty() || "all".equals(normalized)) {
                return ALL;
            }
            for (Status status : values()) {
                if (status.label.equals(normalized)) {
                    return status;
                }
            }
            throw new InsightException(
                InsightError.INVALID_PARAMETER,
                "Parameter 'status' must be one of all, completed, incomplete, got: " + raw + "."
            );
        }

        boolean accepts(Sample sample) {
            return switch (this) {
                case ALL -> true;
                case COMPLETED -> sample.done();
                case INCOMPLETE -> !sample.done();
            };
        }
    }

    /**
     * Copies the advancement tree and the requesting player's progress out of the world owner.
     *
     * <p>The dispatcher only lets this run where a server is active (integrated, or the host
     * answering a bridged request), so definitions and progress always come from the same owner.
     */
    private static List<Sample> capture(RequestContext ctx) throws Exception {
        MinecraftServer server = ctx.executor().getActiveServer();
        try {
            ServerPlayer player = ctx.ownerPlayer(server);
            return ctx.executor().supplyOnServerThread(
                () -> build(server.getAdvancements().getAllAdvancements(), player),
                CAPTURE_TIMEOUT_MS
            );
        } catch (InsightException e) {
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof InsightException insightException) {
                throw insightException;
            }
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "The advancement state could not be read: " + e.getCause()
            );
        } catch (java.util.concurrent.TimeoutException e) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "The server did not hand over the advancement state within " + CAPTURE_TIMEOUT_MS + " ms."
            );
        }
    }

    private static List<Sample> build(Collection<AdvancementHolder> all, ServerPlayer player) {
        PlayerAdvancements progress = player.getAdvancements();
        List<Sample> samples = new ArrayList<>(all.size());
        for (AdvancementHolder holder : all) {
            AdvancementProgress advancementProgress = progress.getOrStartProgress(holder);
            List<String> completed = new ArrayList<>();
            for (String criterion : advancementProgress.getCompletedCriteria()) {
                completed.add(criterion);
            }
            List<String> remaining = new ArrayList<>();
            for (String criterion : advancementProgress.getRemainingCriteria()) {
                remaining.add(criterion);
            }
            samples.add(new Sample(
                holder.id(),
                holder.value(),
                advancementProgress.isDone(),
                advancementProgress.getPercent(),
                List.copyOf(completed),
                List.copyOf(remaining)
            ));
        }
        return List.copyOf(samples);
    }

    // -------------------------------------------------------------- describe

    private static JsonObject describe(Sample sample) {
        Advancement advancement = sample.advancement();
        ResourceLocation id = sample.id();

        JsonObject out = new JsonObject();
        out.addProperty("id", id.toString());
        out.addProperty("modId", id.getNamespace());
        advancement.parent().ifPresent(parent -> out.addProperty("parent", parent.toString()));

        advancement.display().ifPresent(display -> {
            out.addProperty("title", display.getTitle().getString());
            out.addProperty("description", display.getDescription().getString());
            out.addProperty("frame", display.getType().getSerializedName());
            ResourceLocation iconId = BuiltInRegistries.ITEM.getKey(display.getIcon().getItem());
            out.addProperty("icon", iconId.toString());
            out.addProperty("hidden", display.isHidden());
        });

        if (advancement.display().isEmpty()) {
            advancement.name().ifPresent(name -> out.addProperty("title", name.getString()));
        }

        addCriteria(out, advancement);
        JsonArray requirements = describeRequirements(advancement);
        if (!requirements.isEmpty()) {
            out.add("requirements", requirements);
        }
        JsonObject rewards = describeRewards(advancement.rewards());
        if (!rewards.isEmpty()) {
            out.add("rewards", rewards);
        }

        out.add("progress", describeProgress(sample));
        return out;
    }

    private static JsonObject describeCriteria(Advancement advancement) {
        JsonArray names = new JsonArray();
        JsonObject details = new JsonObject();
        for (Map.Entry<String, Criterion<?>> entry : advancement.criteria().entrySet()) {
            names.add(entry.getKey());
            JsonObject detail = new JsonObject();
            ResourceLocation trigger = BuiltInRegistries.TRIGGER_TYPES.getKey(entry.getValue().trigger());
            if (trigger != null) {
                detail.addProperty("trigger", trigger.toString());
            }
            JsonElement conditions = JsonCodec.snapshotObject(entry.getValue().triggerInstance(), SNAPSHOT_DEPTH);
            if (conditions != null && !conditions.isJsonNull()) {
                detail.add("conditions", conditions);
            }
            details.add(entry.getKey(), detail);
        }
        JsonObject out = new JsonObject();
        out.add("names", names);
        if (!details.isEmpty()) {
            out.add("details", details);
        }
        return out;
    }

    /** Adds {@code criteria} and {@code criteriaDetails} to an entry, the shape callers already read. */
    private static void addCriteria(JsonObject entry, Advancement advancement) {
        JsonObject criteria = describeCriteria(advancement);
        entry.add("criteria", criteria.get("names"));
        JsonElement details = criteria.get("details");
        if (details != null) {
            entry.add("criteriaDetails", details);
        }
    }
    private static JsonArray describeRequirements(Advancement advancement) {
        List<List<String>> groups = advancement.requirements().requirements();
        JsonArray out = new JsonArray(groups.size());
        for (List<String> group : groups) {
            JsonArray entry = new JsonArray(group.size());
            for (String criterion : group) {
                entry.add(criterion);
            }
            out.add(entry);
        }
        return out;
    }

    private static JsonObject describeRewards(AdvancementRewards rewards) {
        JsonObject out = new JsonObject();
        if (rewards.experience() > 0) {
            out.addProperty("experience", rewards.experience());
        }
        if (!rewards.loot().isEmpty()) {
            JsonArray loot = new JsonArray();
            for (ResourceKey<?> key : rewards.loot()) {
                loot.add(key.location().toString());
            }
            out.add("loot", loot);
        }
        if (!rewards.recipes().isEmpty()) {
            JsonArray recipes = new JsonArray();
            for (ResourceLocation recipe : rewards.recipes()) {
                recipes.add(recipe.toString());
            }
            out.add("recipes", recipes);
        }
        rewards.function().ifPresent(function -> out.addProperty("function", function.getId().toString()));
        return out;
    }

    private static JsonObject describeProgress(Sample sample) {
        JsonObject out = new JsonObject();
        out.addProperty("done", sample.done());
        out.addProperty("percent", Math.round(sample.percent() * 1000.0F) / 1000.0F);
        JsonArray completed = new JsonArray();
        for (String criterion : sample.completedCriteria()) {
            completed.add(criterion);
        }
        out.add("completedCriteria", completed);
        JsonArray remaining = new JsonArray();
        for (String criterion : sample.remainingCriteria()) {
            remaining.add(criterion);
        }
        out.add("remainingCriteria", remaining);
        return out;
    }

    /**
     * Whether an advancement matches the free-text query.
     *
     * <p>The needle is checked against the fields the caller can see: identity, title, description,
     * icon and criteria. Building that haystack once per entry keeps namespace filtering and text
     * filtering in the same pass over the tree.
     */
    private static boolean matches(JsonObject entry, String lowerQuery) {
        StringBuilder haystack = new StringBuilder();
        append(haystack, entry, "id");
        append(haystack, entry, "title");
        append(haystack, entry, "description");
        append(haystack, entry, "icon");
        JsonElement criteria = entry.get("criteria");
        if (criteria != null && criteria.isJsonArray()) {
            for (JsonElement name : criteria.getAsJsonArray()) {
                haystack.append(name.getAsString()).append(' ');
            }
        }
        return haystack.toString().toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static void append(StringBuilder haystack, JsonObject entry, String key) {
        JsonElement value = entry.get(key);
        if (value != null && value.isJsonPrimitive()) {
            haystack.append(value.getAsString()).append(' ');
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

package net.minetake.mcinsight.introspect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minetake.mcinsight.client.ClientStateAccessor;
import net.minetake.mcinsight.platform.Services;
import net.minetake.mcinsight.server.JsonUtil;
import net.minetake.mcinsight.server.MainThreadExecutor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;

public final class AdvancementCollector {

    private AdvancementCollector() {}

    private record AdvSample(
        ResourceLocation id,
        Advancement advancement,
        boolean hasProgress,
        boolean done,
        float percent,
        List<String> completedCriteria,
        List<String> remainingCriteria
    ) {}

    private record SnapshotResult(boolean hasPlayerProgress, List<AdvSample> samples) {}

    public static JsonObject queryAdvancements(
        MainThreadExecutor executor,
        String query,
        String modId,
        String statusFilter,
        boolean includeRecipes,
        int limit
    ) throws Exception {
        String normQ = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String normMod = modId == null ? "" : modId.trim().toLowerCase(Locale.ROOT);
        String normStatus = statusFilter == null ? "all" : statusFilter.trim().toLowerCase(Locale.ROOT);
        int clampedLimit = Math.max(1, Math.min(limit, 200));

        // Phase 1: Lightweight capture on owning game thread
        SnapshotResult snapshot = captureSnapshot(executor);
        if (snapshot == null) {
            return JsonUtil.errorObject(
                "NO_ADVANCEMENTS",
                "Neither server advancement manager nor client advancements are currently available."
            );
        }

        // Phase 2: Off-thread filtering & JSON serialization
        JsonArray results = new JsonArray();
        boolean hasMore = false;
        for (AdvSample sample : snapshot.samples()) {
            ResourceLocation rl = sample.id();
            if (!includeRecipes && rl.getPath().startsWith("recipes/")) {
                continue;
            }
            if (!normMod.isEmpty() && !rl.getNamespace().toLowerCase(Locale.ROOT).equals(normMod)) {
                continue;
            }
            if ("completed".equals(normStatus)) {
                if (!sample.hasProgress() || !sample.done()) continue;
            } else if ("incomplete".equals(normStatus)) {
                if (!sample.hasProgress() || sample.done()) continue;
            }

            Advancement adv = sample.advancement();
            String idStr = rl.toString();
            String title = null;
            String description = null;
            String frame = null;
            String iconId = null;
            boolean hidden = false;

            if (adv.display().isPresent()) {
                DisplayInfo disp = adv.display().get();
                title = disp.getTitle().getString();
                description = disp.getDescription().getString();
                frame = disp.getType().getSerializedName();
                iconId = BuiltInRegistries.ITEM.getKey(disp.getIcon().getItem()).toString();
                hidden = disp.isHidden();
            }

            if (!normQ.isEmpty()) {
                StringBuilder hay = new StringBuilder();
                hay.append(idStr).append(' ');
                if (title != null) hay.append(title).append(' ');
                if (description != null) hay.append(description).append(' ');
                if (iconId != null) hay.append(iconId).append(' ');
                for (String cKey : adv.criteria().keySet()) {
                    hay.append(cKey).append(' ');
                }
                if (!hay.toString().toLowerCase(Locale.ROOT).contains(normQ)) {
                    continue;
                }
            }

            if (results.size() >= clampedLimit) {
                hasMore = true;
                break;
            }

            JsonObject obj = new JsonObject();
            obj.addProperty("id", idStr);
            obj.addProperty("modId", rl.getNamespace());
            adv.parent().ifPresent(p -> obj.addProperty("parent", p.toString()));
            if (title != null) obj.addProperty("title", title);
            if (description != null) obj.addProperty("description", description);
            if (frame != null) obj.addProperty("frame", frame);
            if (iconId != null) obj.addProperty("icon", iconId);
            if (adv.display().isPresent()) obj.addProperty("hidden", hidden);

            JsonArray criteriaArr = new JsonArray();
            for (String cKey : adv.criteria().keySet()) {
                criteriaArr.add(cKey);
            }
            obj.add("criteria", criteriaArr);

            JsonObject rewardsObj = new JsonObject();
            boolean hasRewards = false;
            if (adv.rewards().experience() > 0) {
                rewardsObj.addProperty("experience", adv.rewards().experience());
                hasRewards = true;
            }
            if (!adv.rewards().loot().isEmpty()) {
                JsonArray lootArr = new JsonArray();
                for (ResourceKey<?> rk : adv.rewards().loot()) {
                    lootArr.add(rk.location().toString());
                }
                rewardsObj.add("loot", lootArr);
                hasRewards = true;
            }
            if (!adv.rewards().recipes().isEmpty()) {
                JsonArray recArr = new JsonArray();
                for (ResourceLocation rec : adv.rewards().recipes()) {
                    recArr.add(rec.toString());
                }
                rewardsObj.add("recipes", recArr);
                hasRewards = true;
            }
            if (hasRewards) {
                obj.add("rewards", rewardsObj);
            }

            if (sample.hasProgress()) {
                JsonObject progObj = new JsonObject();
                progObj.addProperty("done", sample.done());
                progObj.addProperty("percent", Math.round(sample.percent() * 1000.0f) / 1000.0f);
                JsonArray doneArr = new JsonArray();
                for (String s : sample.completedCriteria()) doneArr.add(s);
                progObj.add("completedCriteria", doneArr);
                JsonArray remArr = new JsonArray();
                for (String s : sample.remainingCriteria()) remArr.add(s);
                progObj.add("remainingCriteria", remArr);
                obj.add("progress", progObj);
            }

            results.add(obj);
        }

        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        out.addProperty("hasPlayerProgress", snapshot.hasPlayerProgress());
        out.addProperty("returned", results.size());
        out.addProperty("hasMore", hasMore);
        out.add("advancements", results);
        return out;
    }

    private static SnapshotResult captureSnapshot(MainThreadExecutor executor) throws Exception {
        MinecraftServer server = executor.getActiveServer();
        if (server != null) {
            return executor.supplyOnServerThread(() -> {
                Collection<AdvancementHolder> all = server.getAdvancements().getAllAdvancements();
                ServerPlayer player = null;
                if (GameStateSupport.getPrimaryPlayer(executor) instanceof ServerPlayer sp) {
                    player = sp;
                } else if (!server.getPlayerList().getPlayers().isEmpty()) {
                    player = server.getPlayerList().getPlayers().get(0);
                }
                PlayerAdvancements pa = player != null ? player.getAdvancements() : null;
                List<AdvSample> list = new ArrayList<>(all.size());
                for (AdvancementHolder holder : all) {
                    if (pa != null) {
                        AdvancementProgress prog = pa.getOrStartProgress(holder);
                        List<String> completed = new ArrayList<>();
                        for (String c : prog.getCompletedCriteria()) completed.add(c);
                        List<String> remaining = new ArrayList<>();
                        for (String c : prog.getRemainingCriteria()) remaining.add(c);
                        list.add(new AdvSample(
                            holder.id(),
                            holder.value(),
                            true,
                            prog.isDone(),
                            prog.getPercent(),
                            List.copyOf(completed),
                            List.copyOf(remaining)
                        ));
                    } else {
                        list.add(new AdvSample(holder.id(), holder.value(), false, false, 0f, List.of(), List.of()));
                    }
                }
                return new SnapshotResult(pa != null, list);
            }, 5000);
        }

        if (Services.PLATFORM.isClientSide()) {
            return executor.supplyOnClientThread(() -> {
                List<AdvancementHolder> holders = ClientStateAccessor.captureClientAdvancements();
                if (holders.isEmpty()) {
                    return null;
                }
                List<AdvSample> list = new ArrayList<>(holders.size());
                for (AdvancementHolder holder : holders) {
                    list.add(new AdvSample(holder.id(), holder.value(), false, false, 0f, List.of(), List.of()));
                }
                return new SnapshotResult(false, list);
            }, 5000);
        }

        return null;
    }
}

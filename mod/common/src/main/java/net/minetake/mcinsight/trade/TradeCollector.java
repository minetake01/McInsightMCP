package net.minetake.mcinsight.trade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.ItemFilter;
import net.minetake.mcinsight.api.ItemQuery;
import net.minetake.mcinsight.api.Page;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.cache.CacheGenerations;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;

/**
 * Answers {@code trades_query}: every registered villager and wandering-trader offer.
 *
 * <p>The offers are produced by the game's own {@code ItemListing} implementations, not by reading
 * their fields: those implementations are the definition of what a trade is, including which
 * variant a villager type gets and how price and enchantment randomness land. A listing that
 * declares its offer so abstractly that it cannot be materialized is reported by its declaration
 * through {@link JsonCodec#snapshotObject} instead, which is the same converter the rest of the mod
 * uses.
 *
 * <p>Materializing offers reads registries and, for the listings that need it, the world, so it
 * happens on the game thread and exactly once per data generation.
 */
public final class TradeCollector {

    private static final int SNAPSHOT_DEPTH = 3;
    private static final long SAMPLE_SEED_A = 42L;
    private static final long SAMPLE_SEED_B = 4242L;
    private static final long BUILD_TIMEOUT_MS = 10_000L;

    private static final GenerationalCache<TradeIndex> INDEX = new GenerationalCache<>(CacheGenerations::data);

    private TradeCollector() {}

    /**
     * One trade, in the form both the filter and the answer need.
     *
     * <p>The normalised identifiers and item tags are kept alongside the JSON so that
     * {@code item=} is decided by {@link ItemFilter} rather than by searching the rendered answer.
     */
    private record Trade(
        String professionLower,
        String professionPathLower,
        String professionNamespaceLower,
        Set<String> namespacesLower,
        Set<String> itemIdsLower,
        Set<String> itemTagsLower,
        String searchTextLower,
        JsonObject json
    ) {}

    private record TradeIndex(List<Trade> trades) {}

    /** A stack as it is reported: identity and count, no component payload. */
    private record StackRef(String id, String displayName, int count, Set<String> tagsLower) {}

    private record Offer(
        StackRef costA,
        StackRef costB,
        StackRef result,
        int maxUses,
        int xp,
        float priceMultiplier,
        boolean sampled,
        String villagerType
    ) {}

    public static JsonObject query(RequestContext ctx, Params p) throws Exception {
        int limit = InsightEndpoint.TRADES_QUERY.limit(p);
        int offset = p.offset();
        String query = lower(p.string("q"));
        String profession = lower(p.string("profession"));
        String modId = lower(p.string("mod"));
        String itemRaw = p.string("item");
        ItemFilter item = ItemFilter.of(ItemQuery.optional(itemRaw));

        TradeIndex index = INDEX.get(() -> build(ctx.executor()));

        JsonArray entries = new JsonArray();
        int total = 0;
        for (Trade trade : index.trades()) {
            if (!profession.isEmpty()
                && !trade.professionLower().equals(profession)
                && !trade.professionPathLower().equals(profession)) {
                continue;
            }
            if (!modId.isEmpty()
                && !trade.professionNamespaceLower().equals(modId)
                && !trade.namespacesLower().contains(modId)) {
                continue;
            }
            if (item != null && !tradeMatchesItem(trade, item)) {
                continue;
            }
            if (!query.isEmpty() && !matchesQuery(trade, query)) {
                continue;
            }
            if (total >= offset && entries.size() < limit) {
                entries.add(trade.json());
            }
            total++;
        }
        return Page.of("trades", entries, offset, total);
    }

    /**
     * Whether a trade touches the queried item on either side.
     *
     * <p>A trade is a swap, so the item can appear as something spent or something received;
     * {@code item=} does not guess which the caller meant.
     */
    private static boolean tradeMatchesItem(Trade trade, ItemFilter item) {
        for (String id : trade.itemIdsLower()) {
            if (item.matches(id, trade.itemTagsLower())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesQuery(Trade trade, String lowerQuery) {
        return trade.searchTextLower().contains(lowerQuery);
    }

    // ---------------------------------------------------------------- index

    private static TradeIndex build(MainThreadExecutor executor) {
        try {
            return executor.supplyOnGameThread(() -> buildOnGameThread(executor), BUILD_TIMEOUT_MS);
        } catch (InsightException e) {
            throw e;
        } catch (Exception e) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "The trade index could not be built: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
            );
        }
    }

    private static TradeIndex buildOnGameThread(MainThreadExecutor executor) {
        Level level = GameAccess.getLevel(executor);
        Entity context = level != null ? contextEntity(level) : null;
        RegistryOps<JsonElement> ops = GameAccess.jsonOps(GameAccess.getRegistryAccess(executor));

        List<Trade> trades = new ArrayList<>();
        for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> entry
            : VillagerTrades.TRADES.entrySet()) {
            collect(entry.getKey(), entry.getValue(), false, context, ops, trades);
        }
        for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> entry
            : VillagerTrades.EXPERIMENTAL_TRADES.entrySet()) {
            collect(entry.getKey(), entry.getValue(), true, context, ops, trades);
        }
        collectWanderingTrades(VillagerTrades.WANDERING_TRADER_TRADES, false, context, ops, trades);
        collectExperimentalWanderingTrades(context, ops, trades);
        return new TradeIndex(List.copyOf(trades));
    }

    /**
     * The entity offers are materialized against.
     *
     * <p>Vanilla listings read the level and, for biome-specific trades, the villager's type from
     * the entity they are given. A detached villager supplies both without touching the world:
     * nothing is spawned or added, it exists only for the duration of this build.
     */
    private static Entity contextEntity(Level level) {
        return new Villager(net.minecraft.world.entity.EntityType.VILLAGER, level);
    }

    private static void collect(
        VillagerProfession profession,
        Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel,
        boolean experimental,
        Entity context,
        RegistryOps<JsonElement> ops,
        List<Trade> out
    ) {
        ResourceLocation professionId = BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
        String id = professionId != null ? professionId.toString() : profession.toString();
        for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> entry
            : byLevel.int2ObjectEntrySet()) {
            appendAll(id, entry.getIntKey(), experimental, entry.getValue(), context, ops, out);
        }
    }

    private static void collectWanderingTrades(
        Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel,
        boolean experimental,
        Entity context,
        RegistryOps<JsonElement> ops,
        List<Trade> out
    ) {
        if (byLevel == null) {
            return;
        }
        for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> entry
            : byLevel.int2ObjectEntrySet()) {
            appendAll(WANDERING_TRADER_ID, entry.getIntKey(), experimental, entry.getValue(), context, ops, out);
        }
    }

    private static void collectExperimentalWanderingTrades(
        Entity context,
        RegistryOps<JsonElement> ops,
        List<Trade> out
    ) {
        for (Pair<VillagerTrades.ItemListing[], Integer> entry
            : VillagerTrades.EXPERIMENTAL_WANDERING_TRADER_TRADES) {
            Integer level = entry.getRight();
            appendAll(WANDERING_TRADER_ID, level != null ? level : 1, true, entry.getLeft(), context, ops, out);
        }
    }

    private static final String WANDERING_TRADER_ID = "minecraft:wandering_trader";

    private static void appendAll(
        String professionId,
        int level,
        boolean experimental,
        VillagerTrades.ItemListing[] listings,
        Entity context,
        RegistryOps<JsonElement> ops,
        List<Trade> out
    ) {
        if (listings == null) {
            return;
        }
        for (VillagerTrades.ItemListing listing : listings) {
            if (listing == null) {
                continue;
            }
            append(professionId, level, experimental, listing, context, ops, out);
        }
    }

    private static void append(
        String professionId,
        int level,
        boolean experimental,
        VillagerTrades.ItemListing listing,
        Entity context,
        RegistryOps<JsonElement> ops,
        List<Trade> out
    ) {
        List<Offer> offers = offersOf(listing, context);
        if (offers.isEmpty()) {
            JsonObject json = baseEntry(professionId, level, experimental, listing);
            JsonElement declaration = JsonCodec.snapshotObject(listing, SNAPSHOT_DEPTH, ops);
            if (declaration != null && declaration.isJsonObject()) {
                json.add("listing", declaration);
            }
            out.add(tradeOf(professionId, json, Set.of(), Set.of(), ""));
            return;
        }
        for (Offer offer : offers) {
            JsonObject json = baseEntry(professionId, level, experimental, listing);
            if (offer.villagerType() != null) {
                json.addProperty("villagerType", offer.villagerType());
            }
            json.add("costA", stackJson(offer.costA()));
            if (offer.costB() != null) {
                json.add("costB", stackJson(offer.costB()));
            }
            json.add("result", stackJson(offer.result()));
            json.addProperty("maxUses", offer.maxUses());
            json.addProperty("xp", offer.xp());
            json.addProperty("priceMultiplier", offer.priceMultiplier());
            if (offer.sampled()) {
                json.addProperty("sampled", true);
            }

            Set<String> namespaces = new LinkedHashSet<>();
            namespaces.add(namespaceOf(professionId));
            Set<String> itemIds = new LinkedHashSet<>();
            Set<String> itemTags = new LinkedHashSet<>();
            StringBuilder search = new StringBuilder()
                .append(professionId).append(' ')
                .append(listing.getClass().getName()).append(' ');
            collectStack(offer.costA(), namespaces, itemIds, itemTags, search);
            collectStack(offer.costB(), namespaces, itemIds, itemTags, search);
            collectStack(offer.result(), namespaces, itemIds, itemTags, search);
            if (offer.villagerType() != null) {
                search.append(offer.villagerType()).append(' ');
            }
            out.add(tradeOf(professionId, json, namespaces, itemIds, itemTags, search));
        }
    }

    private static JsonObject baseEntry(
        String professionId,
        int level,
        boolean experimental,
        VillagerTrades.ItemListing listing
    ) {
        JsonObject json = new JsonObject();
        json.addProperty("profession", professionId);
        json.addProperty("level", level);
        if (experimental) {
            json.addProperty("experimental", true);
        }
        json.addProperty("listingClass", listing.getClass().getName());
        return json;
    }

    private static Trade tradeOf(
        String professionId,
        JsonObject json,
        Set<String> namespaces,
        Set<String> itemIds,
        String searchText
    ) {
        return tradeOf(professionId, json, namespaces, itemIds, Set.of(), searchText);
    }

    private static Trade tradeOf(
        String professionId,
        JsonObject json,
        Set<String> namespaces,
        Set<String> itemIds,
        Set<String> itemTags,
        CharSequence searchText
    ) {
        String professionLower = professionId.toLowerCase(Locale.ROOT);
        int colon = professionId.indexOf(':');
        String path = colon >= 0 ? professionId.substring(colon + 1) : professionId;
        String namespace = namespaceOf(professionId);
        Set<String> allNamespaces = new LinkedHashSet<>(namespaces);
        allNamespaces.add(namespace);
        return new Trade(
            professionLower,
            path.toLowerCase(Locale.ROOT),
            namespace,
            Set.copyOf(allNamespaces),
            Set.copyOf(itemIds),
            Set.copyOf(itemTags),
            searchText.toString().toLowerCase(Locale.ROOT),
            json
        );
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return (colon > 0 ? id.substring(0, colon) : "minecraft").toLowerCase(Locale.ROOT);
    }

    // -------------------------------------------------------------- offers

    /**
     * Vanilla listings whose class is not public, so it cannot be named from here.
     *
     * <p>Their behaviour is reachable only through {@link VillagerTrades.ItemListing#getOffer}, so
     * they are recognised by name and treated as ordinary listings; naming the class in an
     * {@code instanceof} would not compile.
     */
    private static final Set<String> TYPE_SENSITIVE_LISTINGS = Set.of(
        "TypeSpecificTrade",
        "EmeraldsForVillagerTypeItem"
    );

    private static final Set<String> WORLD_SEARCHING_LISTINGS = Set.of(
        "TreasureMapForEmeralds"
    );

    /**
     * Materializes a listing into the offers it can produce.
     *
     * <p>Biome-specific listings carry one sub-listing per villager type; each type is materialized
     * so a caller sees the jungle trade and the desert trade rather than one of them standing in
     * for both. A listing whose offer depends on a random source is sampled twice and marked, so a
     * caller can tell a fixed trade from one that varies per villager.
     */
    private static List<Offer> offersOf(VillagerTrades.ItemListing listing, Entity context) {
        if (context == null || searchesTheWorld(listing)) {
            return List.of();
        }
        if (TYPE_SENSITIVE_LISTINGS.contains(listing.getClass().getSimpleName())) {
            return offersByVillagerType(listing, context);
        }
        Offer offer = materialize(listing, context, null);
        return offer != null ? List.of(offer) : List.of();
    }

    /**
     * Listings whose offer is produced by searching the world.
     *
     * <p>Materializing one asks the level for the nearest matching structure and builds a filled
     * map, which is both unbounded work and a write into the level's map storage. Its declaration
     * is reported instead, so the answer stays a description and the query stays read-only.
     */
    private static boolean searchesTheWorld(VillagerTrades.ItemListing listing) {
        return WORLD_SEARCHING_LISTINGS.contains(listing.getClass().getSimpleName());
    }

    private static List<Offer> offersByVillagerType(VillagerTrades.ItemListing listing, Entity context) {
        Level level = context.level();
        List<Offer> offers = new ArrayList<>();
        for (VillagerType type : BuiltInRegistries.VILLAGER_TYPE) {
            ResourceLocation typeId = BuiltInRegistries.VILLAGER_TYPE.getKey(type);
            Entity typed = new Villager(net.minecraft.world.entity.EntityType.VILLAGER, level, type);
            Offer offer = materialize(listing, typed, typeId != null ? typeId.toString() : type.toString());
            if (offer != null) {
                offers.add(offer);
            }
        }
        return offers;
    }

    private static Offer materialize(VillagerTrades.ItemListing listing, Entity context, String villagerType) {
        MerchantOffer first = singleOffer(listing, context, SAMPLE_SEED_A);
        if (first == null) {
            return null;
        }
        MerchantOffer second = singleOffer(listing, context, SAMPLE_SEED_B);
        boolean sampled = second == null || !sameIo(first, second);
        return new Offer(
            stackRef(first.getCostA()),
            first.getCostB().isEmpty() ? null : stackRef(first.getCostB()),
            stackRef(first.getResult()),
            first.getMaxUses(),
            first.getXp(),
            first.getPriceMultiplier(),
            sampled,
            villagerType
        );
    }

    private static MerchantOffer singleOffer(VillagerTrades.ItemListing listing, Entity context, long seed) {
        try {
            return listing.getOffer(context, RandomSource.create(seed));
        } catch (RuntimeException e) {
            // A listing that cannot be materialized without more world context keeps its
            // declaration; that is reported, not guessed.
            return null;
        }
    }

    private static boolean sameIo(MerchantOffer a, MerchantOffer b) {
        return sameStack(a.getCostA(), b.getCostA())
            && sameStack(a.getCostB(), b.getCostB())
            && sameStack(a.getResult(), b.getResult())
            && a.getMaxUses() == b.getMaxUses()
            && a.getXp() == b.getXp()
            && Float.compare(a.getPriceMultiplier(), b.getPriceMultiplier()) == 0;
    }

    private static boolean sameStack(ItemStack a, ItemStack b) {
        if (a.isEmpty() != b.isEmpty()) {
            return false;
        }
        if (a.isEmpty()) {
            return true;
        }
        return a.getItem() == b.getItem() && a.getCount() == b.getCount();
    }

    private static StackRef stackRef(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        Set<String> tags = new LinkedHashSet<>();
        stack.getTags().forEach(tag -> tags.add(tag.location().toString().toLowerCase(Locale.ROOT)));
        return new StackRef(id.toString(), stack.getHoverName().getString(), stack.getCount(), Set.copyOf(tags));
    }

    /** The reported form of a stack: what it is and how many, without its component payload. */
    private static JsonObject stackJson(StackRef stack) {
        JsonObject json = new JsonObject();
        if (stack == null) {
            json.addProperty("empty", true);
            return json;
        }
        json.addProperty("id", stack.id());
        json.addProperty("displayName", stack.displayName());
        json.addProperty("count", stack.count());
        return json;
    }

    private static void collectStack(
        StackRef stack,
        Set<String> namespaces,
        Set<String> itemIds,
        Set<String> itemTags,
        StringBuilder search
    ) {
        if (stack == null) {
            return;
        }
        String lowerId = stack.id().toLowerCase(Locale.ROOT);
        itemIds.add(lowerId);
        itemTags.addAll(stack.tagsLower());
        namespaces.add(namespaceOf(lowerId));
        search.append(stack.id()).append(' ').append(stack.displayName()).append(' ');
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

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
        List<StackRef> costs,
        List<StackRef> results,
        String searchTextLower,
        JsonObject json
    ) {}

    /** Which side of a trade {@code item=} must be on: what the player pays, or what they get. */
    private enum Side {
        ANY, COST, RESULT;

        static Side parse(String raw) {
            if (raw == null) {
                return ANY;
            }
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "any" -> ANY;
                case "cost" -> COST;
                case "result" -> RESULT;
                default -> throw new InsightException(
                    InsightError.INVALID_PARAMETER,
                    "Parameter 'side' must be one of any, cost, result, got: " + raw + "."
                );
            };
        }
    }

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
        ItemFilter item = ItemFilter.of(ItemQuery.optional(p.string("item")));
        Side side = Side.parse(p.string("side"));
        if (item == null && side != Side.ANY) {
            throw new InsightException(InsightError.INVALID_PARAMETER, "Parameter 'side' needs 'item'.");
        }

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
            if (item != null && !tradeMatchesItem(trade, item, side)) {
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
     * Whether the queried item is on the requested side of a trade.
     *
     * <p>Each stack is matched with its own tags, so a cost tagged {@code #c:gems} does not make the
     * result count as a gem. A listing that could only be described, not rolled, has no stacks and
     * never matches an item; it stays reachable through {@code q}.
     */
    private static boolean tradeMatchesItem(Trade trade, ItemFilter item, Side side) {
        return (side != Side.RESULT && anyMatches(trade.costs(), item))
            || (side != Side.COST && anyMatches(trade.results(), item));
    }

    private static boolean anyMatches(List<StackRef> stacks, ItemFilter item) {
        for (StackRef stack : stacks) {
            if (item.matchesItem(stack.id(), stack.tagsLower())) {
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
        Entity context = level != null ? villagerOf(level, VillagerType.PLAINS) : null;
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
        for (Variant variant : variantsOf(listing, context)) {
            Offer offer = variant.trader() != null ? materialize(variant) : null;
            if (offer == null) {
                out.add(declarationOf(professionId, level, experimental, variant, ops));
            } else {
                out.add(offerOf(professionId, level, experimental, variant.listing(), offer));
            }
        }
    }

    /** A listing that could not be turned into an offer, reported by what it declares. */
    private static Trade declarationOf(
        String professionId,
        int level,
        boolean experimental,
        Variant variant,
        RegistryOps<JsonElement> ops
    ) {
        JsonObject json = baseEntry(professionId, level, experimental, variant.listing());
        if (variant.villagerType() != null) {
            json.addProperty("villagerType", variant.villagerType());
        }
        JsonElement declaration = JsonCodec.snapshotObject(variant.listing(), SNAPSHOT_DEPTH, ops);
        if (declaration != null && declaration.isJsonObject()) {
            json.add("listing", declaration);
        }
        String search = professionId + ' ' + variant.listing().getClass().getName()
            + (variant.villagerType() != null ? ' ' + variant.villagerType() : "");
        return tradeOf(professionId, json, Set.of(), List.of(), List.of(), search);
    }

    private static Trade offerOf(
        String professionId,
        int level,
        boolean experimental,
        VillagerTrades.ItemListing listing,
        Offer offer
    ) {
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
        StringBuilder search = new StringBuilder()
            .append(professionId).append(' ')
            .append(listing.getClass().getName()).append(' ');
        List<StackRef> costs = new ArrayList<>(2);
        for (StackRef cost : new StackRef[] {offer.costA(), offer.costB()}) {
            if (cost != null) {
                costs.add(cost);
                describeStack(cost, namespaces, search);
            }
        }
        List<StackRef> results = offer.result() != null ? List.of(offer.result()) : List.of();
        results.forEach(r -> describeStack(r, namespaces, search));
        if (offer.villagerType() != null) {
            search.append(offer.villagerType()).append(' ');
        }
        return tradeOf(professionId, json, namespaces, costs, results, search);
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
        List<StackRef> costs,
        List<StackRef> results,
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
            List.copyOf(costs),
            List.copyOf(results),
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
     * One concrete listing as a villager of one type would see it.
     *
     * <p>{@code trader} is null when the listing must not be materialized here; the variant is
     * then reported by its declaration.
     */
    private record Variant(VillagerTrades.ItemListing listing, String villagerType, Entity trader) {}

    /**
     * What a listing's own fields say about how it produces an offer.
     *
     * <p>Vanilla's listing classes are package-private and their names differ between loaders'
     * runtime mappings, so they are recognised by the values they hold, not by class name. Those
     * values are also what decides the behaviour: a structure tag is what gets searched for, and a
     * villager-type map is what the offer is looked up in.
     *
     * @param byVillagerType the listing's villager-type map, or null when the offer ignores type
     * @param searchesTheWorld whether the listing names a structure to locate
     */
    private record Shape(Map<?, ?> byVillagerType, boolean searchesTheWorld) {}

    /**
     * Splits a listing into the concrete listings villagers actually roll.
     *
     * <p>A map from villager type to listing is a biome wrapper and is unwrapped before anything is
     * rolled, so the world-search check sees the listing that runs rather than its wrapper: vanilla
     * wraps its cartographer treasure maps that way. A map from villager type to anything else is
     * read by the listing itself, so it is rolled once per type in the map. Either way a caller sees
     * the jungle trade and the desert trade rather than one standing in for both.
     */
    private static List<Variant> variantsOf(VillagerTrades.ItemListing listing, Entity context) {
        Shape shape = shapeOf(listing);
        if (context == null || shape.searchesTheWorld()) {
            return List.of(new Variant(listing, null, null));
        }
        if (shape.byVillagerType() == null) {
            return List.of(new Variant(listing, null, context));
        }
        List<Variant> variants = new ArrayList<>(shape.byVillagerType().size());
        for (Map.Entry<?, ?> entry : shape.byVillagerType().entrySet()) {
            VillagerType type = (VillagerType) entry.getKey();
            if (entry.getValue() instanceof VillagerTrades.ItemListing inner) {
                variants.addAll(variantsOf(inner, villagerOf(context.level(), type)).stream()
                    .map(v -> new Variant(v.listing(), typeId(type), v.trader()))
                    .toList());
            } else {
                variants.add(new Variant(listing, typeId(type), villagerOf(context.level(), type)));
            }
        }
        return variants;
    }

    /**
     * Reads a listing's instance fields once to find its villager-type map and any structure tag.
     *
     * <p>A field that cannot be opened is a field whose meaning is unknown; the listing is then
     * treated as one that may search the world, so it is described rather than run.
     */
    private static Shape shapeOf(VillagerTrades.ItemListing listing) {
        Map<?, ?> byType = null;
        boolean searches = false;
        for (java.lang.reflect.Field field : listing.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            if (!Map.class.isAssignableFrom(type) && !net.minecraft.tags.TagKey.class.isAssignableFrom(type)) {
                continue;
            }
            Object value;
            try {
                if (!field.trySetAccessible()) {
                    return new Shape(null, true);
                }
                value = field.get(listing);
            } catch (IllegalAccessException e) {
                return new Shape(null, true);
            }
            if (value instanceof net.minecraft.tags.TagKey<?> tag
                && tag.registry().equals(net.minecraft.core.registries.Registries.STRUCTURE)) {
                searches = true;
            } else if (value instanceof Map<?, ?> map && !map.isEmpty()
                && map.keySet().stream().allMatch(VillagerType.class::isInstance)) {
                byType = map;
            }
        }
        return new Shape(byType, searches);
    }

    /**
     * The entity offers are rolled against.
     *
     * <p>Listings read the level and the villager's type from the entity they are given. A detached
     * villager supplies both without touching the world: nothing is spawned or added.
     */
    private static Entity villagerOf(Level level, VillagerType type) {
        return new Villager(net.minecraft.world.entity.EntityType.VILLAGER, level, type);
    }

    private static String typeId(VillagerType type) {
        ResourceLocation id = BuiltInRegistries.VILLAGER_TYPE.getKey(type);
        return id != null ? id.toString() : type.toString();
    }

    /**
     * Rolls a variant twice; a listing whose offer depends on the random source differs between
     * the rolls and is marked, so a caller can tell a fixed trade from one that varies per villager.
     */
    private static Offer materialize(Variant variant) {
        VillagerTrades.ItemListing listing = variant.listing();
        Entity context = variant.trader();
        String villagerType = variant.villagerType();
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

    private static void describeStack(StackRef stack, Set<String> namespaces, StringBuilder search) {
        namespaces.add(namespaceOf(stack.id()));
        search.append(stack.id()).append(' ').append(stack.displayName()).append(' ');
    }
    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

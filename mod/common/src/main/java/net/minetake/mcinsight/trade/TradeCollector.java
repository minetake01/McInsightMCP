package net.minetake.mcinsight.trade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.api.InsightError;
import net.minetake.mcinsight.api.InsightException;
import net.minetake.mcinsight.api.ItemFilter;
import net.minetake.mcinsight.api.ItemQuery;
import net.minetake.mcinsight.api.Page;
import net.minetake.mcinsight.api.Params;
import net.minetake.mcinsight.api.RequestContext;
import net.minetake.mcinsight.cache.GenerationalCache;
import net.minetake.mcinsight.game.GameAccess;
import net.minetake.mcinsight.game.MainThreadExecutor;
import net.minetake.mcinsight.game.Versioned;
import net.minetake.mcinsight.serial.JsonCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
//? if >=26.3 {
/*import net.minecraft.world.entity.EntityTypes;
*///? }
//? if >=26.1 {
/*import net.minecraft.core.HolderSet;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.TradeSets;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
*///? } else {
import net.minecraft.world.entity.npc.VillagerTrades;
import org.apache.commons.lang3.tuple.Pair;
//? }

/**
 * Answers {@code trades_query}: every registered villager and wandering-trader offer.
 *
 * <p>How a trade is reported is the same in every version; where trades come from is not. Up to
 * 1.21.1 each profession holds a table of {@code ItemListing} objects that roll an offer against an
 * entity; from 26.1 the trades are named registry entries grouped into a trade set per profession
 * and level, rolled against a loot context. That is a replaced subsystem rather than a renamed one,
 * so {@link #readTrades} asks the same question of the two systems and only the matching answer is
 * compiled; everything that reports a trade is shared.
 *
 * <p>Either way the offer is produced by the game, not by reading its fields: those listings and
 * registry entries are the definition of what a trade is, including which variant a villager type
 * gets and how price and enchantment randomness land. A trade that declares its offer so abstractly
 * that it cannot be rolled is reported by its declaration through {@link JsonCodec#snapshotObject},
 * which is the same converter the rest of the mod uses.
 *
 * <p>Reading touches registries and, for a listing that names a structure, the world, so it happens
 * on the game thread and exactly once per data generation.
 */
public final class TradeCollector {

    private static final long BUILD_TIMEOUT_MS = 10_000L;
    private static final int SNAPSHOT_DEPTH = 3;
    private static final long SAMPLE_SEED_A = 42L;
    private static final long SAMPLE_SEED_B = 4242L;
    private static final String WANDERING_TRADER_ID = "minecraft:wandering_trader";

    private static final GenerationalCache<TradeIndex> INDEX = new GenerationalCache<>(GenerationalCache.Scope.DATA);

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
     * result count as a gem. A trade that could only be described, not rolled, has no stacks and
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
        HolderLookup.Provider registries = GameAccess.getRegistryAccess(executor);
        RegistryOps<JsonElement> ops = GameAccess.jsonOps(registries);
        List<TradeOffer> offers = readTrades(level, registries, ops);
        List<Trade> trades = new ArrayList<>(offers.size());
        for (TradeOffer offer : offers) {
            trades.add(tradeOf(offer));
        }
        return new TradeIndex(List.copyOf(trades));
    }

    /** Every trade the running game knows about, as offers. */
    private static List<TradeOffer> readTrades(Level level, HolderLookup.Provider registries, RegistryOps<JsonElement> ops) {
        //? if >=26.1 {
        /*return readRegistryTrades(level, registries, ops);
        *///? } else {
        return readListingTrades(level, ops);
        //? }
    }

    private static Trade tradeOf(TradeOffer offer) {
        String professionId = offer.professionId();
        JsonObject json = new JsonObject();
        json.addProperty("profession", professionId);
        if (offer.level() != null) {
            json.addProperty("level", offer.level());
        }
        if (offer.experimental()) {
            json.addProperty("experimental", true);
        }
        json.addProperty("listingClass", offer.listingClass());
        if (offer.tradeId() != null) {
            json.addProperty("tradeId", offer.tradeId());
        }
        if (offer.villagerType() != null) {
            json.addProperty("villagerType", offer.villagerType());
        }

        StringBuilder search = new StringBuilder()
            .append(professionId).append(' ')
            .append(offer.listingClass()).append(' ');
        if (offer.villagerType() != null) {
            search.append(offer.villagerType()).append(' ');
        }
        if (offer.tradeId() != null) {
            search.append(offer.tradeId()).append(' ');
        }

        Set<String> namespaces = new LinkedHashSet<>();
        List<StackRef> costs = new ArrayList<>(2);
        List<StackRef> results = List.of();

        if (offer.declaration() != null) {
            json.add("listing", offer.declaration());
            search.append(offer.declaration()).append(' ');
        } else {
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
            for (StackRef cost : new StackRef[] {offer.costA(), offer.costB()}) {
                if (cost != null) {
                    costs.add(cost);
                    describeStack(cost, namespaces, search);
                }
            }
            results = offer.result() != null ? List.of(offer.result()) : List.of();
            results.forEach(stack -> describeStack(stack, namespaces, search));
        }

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
            search.toString().toLowerCase(Locale.ROOT),
            json
        );
    }

    // -------------------------------------------------------------- renders

    /** The reported form of a stack: what it is, how many, and any non-default components. */
    private static JsonObject stackJson(StackRef stack) {
        JsonObject json = new JsonObject();
        if (stack == null) {
            json.addProperty("empty", true);
            return json;
        }
        json.addProperty("id", stack.id());
        json.addProperty("displayName", stack.displayName());
        json.addProperty("count", stack.count());
        if (stack.components() != null) {
            json.add("components", stack.components());
        }
        return json;
    }

    private static void describeStack(StackRef stack, Set<String> namespaces, StringBuilder search) {
        namespaces.add(namespaceOf(stack.id()));
        search.append(stack.id()).append(' ').append(stack.displayName()).append(' ');
        if (stack.components() != null) {
            search.append(stack.components()).append(' ');
        }
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return (colon > 0 ? id.substring(0, colon) : "minecraft").toLowerCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------- rolling, shared

    /**
     * A rolled offer, before it is attached to the profession and level it was rolled for.
     *
     * <p>An offer that could not be rolled a second time is still the game's offer for the first
     * roll; marking it sampled says a second roll did not reproduce it, which is the honest
     * description of an offer that changed under the same seed.
     */
    private static TradeOffer rolledOffer(
        String listingClass,
        MerchantOffer first,
        MerchantOffer second,
        RegistryOps<JsonElement> ops
    ) {
        boolean sampled = second == null || !sameIo(first, second);
        return new TradeOffer(
            null,
            null,
            false,
            listingClass,
            null,
            StackRef.of(first.getCostA(), ops),
            first.getCostB().isEmpty() ? null : StackRef.of(first.getCostB(), ops),
            StackRef.of(first.getResult(), ops),
            first.getMaxUses(),
            first.getXp(),
            first.getPriceMultiplier(),
            sampled,
            null,
            null
        );
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
        return a.isEmpty() || (a.getCount() == b.getCount() && ItemStack.isSameItemSameComponents(a, b));
    }

    /** A declaration worth reporting, or null when the snapshot produced nothing usable. */
    private static JsonElement jsonOf(JsonElement declaration) {
        return declaration != null && declaration.isJsonObject() ? declaration : null;
    }

    /**
     * The villager an offer is rolled against.
     *
     * <p>A listing and a trade both read the level and the villager's type from the entity they are
     * given. A detached villager supplies both without touching the world: nothing is spawned or
     * added. 26.3 moved the entity type constants out of {@code EntityType} into {@code EntityTypes}.
     */
    //? if >=26.3 {
    /*private static Villager villagerOf(Level level, Holder.Reference<VillagerType> type) {
        Villager villager = new Villager(EntityTypes.VILLAGER, level);
        villager.setVillagerData(villager.getVillagerData().withType(type));
        return villager;
    }
    *///? } else if >=26.1 {
    /*private static Villager villagerOf(Level level, Holder.Reference<VillagerType> type) {
        return new Villager(EntityType.VILLAGER, level, type);
    }
    *///? } else {
    private static Villager villagerOf(Level level, VillagerType type) {
        return new Villager(EntityType.VILLAGER, level, type);
    }
    //? }

    /** The trades a set offers; 26.3 made the accessor match the record's component name. */
    //? if >=26.3 {
    /*private static HolderSet<VillagerTrade> tradeSetMembers(TradeSet set) {
        return set.trades();
    }
    *///? } else if >=26.1 {
    /*private static HolderSet<VillagerTrade> tradeSetMembers(TradeSet set) {
        return set.getTrades();
    }
    *///? }

    // -------------------------------------------------- source: 26.1 and later

    // The 26.x trade subsystem: named registry entries, grouped into a set per profession and level.
    // A trade is limited to villager types by a loot condition on the entity, so it is offered to
    // each type in turn and reported against the ones it survives. A trade every type can roll is
    // added once with no type, because the type is then not part of what the trade is, and a trade
    // no type can roll is reported by its declaration.
    //? if >=26.1 {
    /*private static List<TradeOffer> readRegistryTrades(Level level, HolderLookup.Provider registries, RegistryOps<JsonElement> ops) {
        if (!(level instanceof ServerLevel serverLevel) || registries == null) {
            throw new InsightException(
                InsightError.DATA_UNAVAILABLE,
                "Trade definitions require an active server level."
            );
        }
        HolderLookup.RegistryLookup<TradeSet> tradeSets = registries.lookupOrThrow(Registries.TRADE_SET);
        List<Holder.Reference<VillagerType>> villagerTypes =
            registries.lookupOrThrow(Registries.VILLAGER_TYPE).listElements().toList();
        Map<ResourceLocation, Villager> villagersByType = new HashMap<>(villagerTypes.size() * 2);

        List<TradeOffer> out = new ArrayList<>();
        for (Holder.Reference<VillagerProfession> profession
            : registries.lookupOrThrow(Registries.VILLAGER_PROFESSION).listElements().toList()) {
            String professionId = Versioned.idOf(profession.key()).toString();
            Int2ObjectMap<ResourceKey<TradeSet>> byLevel = profession.value().tradeSetsByLevel();
            for (Int2ObjectMap.Entry<ResourceKey<TradeSet>> entry : byLevel.int2ObjectEntrySet()) {
                TradeSet set = tradeSets.get(entry.getValue()).map(Holder.Reference::value).orElse(null);
                if (set == null) {
                    continue;
                }
                for (Holder<VillagerTrade> trade : tradeSetMembers(set)) {
                    String tradeId = trade.unwrapKey().map(key -> Versioned.idOf(key).toString()).orElse(null);
                    collectRegistryTrade(serverLevel, tradeId, trade.value(), villagerTypes, villagersByType,
                        professionId, entry.getIntKey(), ops, out);
                }
            }
        }
        for (ResourceKey<TradeSet> key : List.of(
            TradeSets.WANDERING_TRADER_BUYING,
            TradeSets.WANDERING_TRADER_COMMON,
            TradeSets.WANDERING_TRADER_UNCOMMON
        )) {
            TradeSet set = tradeSets.get(key).map(Holder.Reference::value).orElse(null);
            if (set == null) {
                continue;
            }
            for (Holder<VillagerTrade> trade : tradeSetMembers(set)) {
                String tradeId = trade.unwrapKey().map(k -> Versioned.idOf(k).toString()).orElse(null);
                collectRegistryTrade(serverLevel, tradeId, trade.value(), villagerTypes, villagersByType,
                    WANDERING_TRADER_ID, null, ops, out);
            }
        }
        return out;
    }

    private static void collectRegistryTrade(
        ServerLevel level,
        String tradeId,
        VillagerTrade trade,
        List<Holder.Reference<VillagerType>> types,
        Map<ResourceLocation, Villager> villagersByType,
        String professionId,
        Integer tradeLevel,
        RegistryOps<JsonElement> ops,
        List<TradeOffer> out
    ) {
        JsonElement encoded = encodeRegistryTrade(trade, ops);
        if (encoded != null && encoded.toString().toLowerCase(Locale.ROOT).contains("exploration_map")) {
            out.add(new TradeOffer(
                professionId, tradeLevel, false, VillagerTrade.class.getName(), tradeId,
                null, null, null, 0, 0, 0.0F, false, null, null
            ).described(jsonOf(encoded)));
            return;
        }
        List<TradeOffer> rolled = new ArrayList<>();
        for (Holder.Reference<VillagerType> type : types) {
            Villager villager = villagersByType.computeIfAbsent(
                Versioned.idOf(type.key()),
                ignored -> villagerOf(level, type)
            );
            TradeOffer offer = materializeRegistryTrade(
                level, villager, trade,
                professionId, tradeLevel, tradeId, Versioned.idOf(type.key()).toString(), ops
            );
            if (offer != null) {
                rolled.add(offer);
            }
        }
        if (rolled.isEmpty()) {
            JsonElement declaration = encoded != null ? encoded : JsonCodec.snapshotObject(trade, SNAPSHOT_DEPTH, ops);
            out.add(new TradeOffer(
                professionId, tradeLevel, false, VillagerTrade.class.getName(), tradeId,
                null, null, null, 0, 0, 0.0F, false, null, null
            ).described(jsonOf(declaration)));
            return;
        }
        if (rolled.size() == types.size() && allOffersIdentical(rolled)) {
            out.add(rolled.get(0).attached(professionId, tradeLevel, false, tradeId, null));
            return;
        }
        out.addAll(rolled);
    }

    private static JsonElement encodeRegistryTrade(VillagerTrade trade, RegistryOps<JsonElement> ops) {
        if (ops == null) {
            return null;
        }
        try {
            return VillagerTrade.CODEC.encodeStart(ops, trade).result().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean allOffersIdentical(List<TradeOffer> rolled) {
        if (rolled.size() <= 1) {
            return true;
        }
        TradeOffer first = rolled.get(0);
        for (int i = 1; i < rolled.size(); i++) {
            TradeOffer other = rolled.get(i);
            if (!Objects.equals(first.costA(), other.costA())
                || !Objects.equals(first.costB(), other.costB())
                || !Objects.equals(first.result(), other.result())
                || first.maxUses() != other.maxUses()
                || first.xp() != other.xp()
                || Float.compare(first.priceMultiplier(), other.priceMultiplier()) != 0
                || first.sampled() != other.sampled()) {
                return false;
            }
        }
        return true;
    }

    private static TradeOffer materializeRegistryTrade(
        ServerLevel level,
        Villager villager,
        VillagerTrade trade,
        String professionId,
        Integer tradeLevel,
        String tradeId,
        String villagerType,
        RegistryOps<JsonElement> ops
    ) {
        MerchantOffer first = registryOffer(level, villager, trade, SAMPLE_SEED_A);
        if (first == null) {
            return null;
        }
        MerchantOffer second = registryOffer(level, villager, trade, SAMPLE_SEED_B);
        return rolledOffer(VillagerTrade.class.getName(), first, second, ops)
            .attached(professionId, tradeLevel, false, tradeId, villagerType);
    }

    private static MerchantOffer registryOffer(ServerLevel level, Villager villager, VillagerTrade trade, long seed) {
        LootParams params = new LootParams.Builder(level)
            .withParameter(LootContextParams.ORIGIN, villager.position())
            .withParameter(LootContextParams.THIS_ENTITY, villager)
            .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
            .create(LootContextParamSets.VILLAGER_TRADE);
        LootContext context = new LootContext.Builder(params)
            .withOptionalRandomSource(RandomSource.create(seed))
            .create(java.util.Optional.empty());
        try {
            return trade.getOffer(context);
        } catch (RuntimeException e) {
            // A trade whose condition cannot be read from this villager is reported by its
            // declaration; guessing a stack would report an offer the game never makes.
            return null;
        }
    }
    *///? }

    // --------------------------------------------------- source: up to 1.21.1

    // The 1.21.1 trade subsystem: a table of listings per profession and level. A listing that holds
    // a villager-type map is expanded per type, and a listing that names a structure tag is reported
    // by its declaration rather than run, because finding a map target searches the world and writes
    // into map storage.
    //? if <26.1 {
    private static List<TradeOffer> readListingTrades(Level level, RegistryOps<JsonElement> ops) {
        Entity context = villagerOf(level, VillagerType.PLAINS);
        List<TradeOffer> out = new ArrayList<>();
        for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> entry
            : VillagerTrades.TRADES.entrySet()) {
            collectListings(professionIdOf(entry.getKey()), entry.getValue(), false, context, ops, out);
        }
        for (Map.Entry<VillagerProfession, Int2ObjectMap<VillagerTrades.ItemListing[]>> entry
            : VillagerTrades.EXPERIMENTAL_TRADES.entrySet()) {
            collectListings(professionIdOf(entry.getKey()), entry.getValue(), true, context, ops, out);
        }
        collectListings(WANDERING_TRADER_ID, VillagerTrades.WANDERING_TRADER_TRADES, false, context, ops, out);
        for (Pair<VillagerTrades.ItemListing[], Integer> entry : VillagerTrades.EXPERIMENTAL_WANDERING_TRADER_TRADES) {
            int entryLevel = entry.getRight() != null ? entry.getRight() : 1;
            appendListings(WANDERING_TRADER_ID, entryLevel, true, entry.getLeft(), context, ops, out);
        }
        return out;
    }

    private static String professionIdOf(VillagerProfession profession) {
        return BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).toString();
    }

    private static void collectListings(
        String professionId,
        Int2ObjectMap<VillagerTrades.ItemListing[]> byLevel,
        boolean experimental,
        Entity context,
        RegistryOps<JsonElement> ops,
        List<TradeOffer> out
    ) {
        if (byLevel == null) {
            return;
        }
        for (Int2ObjectMap.Entry<VillagerTrades.ItemListing[]> entry : byLevel.int2ObjectEntrySet()) {
            appendListings(professionId, entry.getIntKey(), experimental, entry.getValue(), context, ops, out);
        }
    }

    private static void appendListings(
        String professionId,
        int level,
        boolean experimental,
        VillagerTrades.ItemListing[] listings,
        Entity context,
        RegistryOps<JsonElement> ops,
        List<TradeOffer> out
    ) {
        if (listings == null) {
            return;
        }
        for (VillagerTrades.ItemListing listing : listings) {
            if (listing == null) {
                continue;
            }
            for (ListingVariant variant : variantsOf(listing, context)) {
                TradeOffer offer = variant.trader() == null
                    ? null
                    : materializeListing(professionId, level, experimental, variant, ops);
                out.add(offer != null
                    ? offer
                    : describeListing(professionId, level, experimental, variant, ops));
            }
        }
    }

    // One concrete listing as a villager of one type would see it. `trader` is null when the listing
    // must not be materialized here; the variant is then reported by its declaration.
    private record ListingVariant(VillagerTrades.ItemListing listing, String villagerType, Entity trader) {}

    // What a listing's own fields say about how it produces an offer.
    //
    // Vanilla's listing classes are package-private and their names differ between loaders' runtime
    // mappings, so they are recognised by the values they hold, not by class name. Those values are
    // also what decides the behaviour: a structure tag is what gets searched for, and a villager-type
    // map is what the offer is looked up in.
    private record ListingShape(Map<?, ?> byVillagerType, boolean searchesTheWorld) {}

    // Splits a listing into the concrete listings villagers actually roll.
    //
    // A map from villager type to listing is a biome wrapper and is unwrapped before anything is
    // rolled, so the world-search check sees the listing that runs rather than its wrapper: vanilla
    // wraps its cartographer treasure maps that way. A map from villager type to anything else is
    // read by the listing itself, so it is rolled once per type in the map. Either way a caller sees
    // the jungle trade and the desert trade rather than one standing in for both.
    private static List<ListingVariant> variantsOf(VillagerTrades.ItemListing listing, Entity context) {
        ListingShape shape = shapeOf(listing);
        if (context == null || shape.searchesTheWorld()) {
            return List.of(new ListingVariant(listing, null, null));
        }
        if (shape.byVillagerType() == null) {
            return List.of(new ListingVariant(listing, null, context));
        }
        List<ListingVariant> variants = new ArrayList<>(shape.byVillagerType().size());
        for (Map.Entry<?, ?> entry : shape.byVillagerType().entrySet()) {
            VillagerType type = (VillagerType) entry.getKey();
            if (entry.getValue() instanceof VillagerTrades.ItemListing inner) {
                for (ListingVariant variant : variantsOf(inner, villagerOf(context.level(), type))) {
                    variants.add(new ListingVariant(variant.listing(), typeId(type), variant.trader()));
                }
            } else {
                variants.add(new ListingVariant(listing, typeId(type), villagerOf(context.level(), type)));
            }
        }
        return variants;
    }

    // Reads a listing's instance fields once to find its villager-type map and any structure tag.
    //
    // A field that cannot be opened is a field whose meaning is unknown; the listing is then treated
    // as one that may search the world, so it is described rather than run.
    private static ListingShape shapeOf(VillagerTrades.ItemListing listing) {
        Map<?, ?> byType = null;
        boolean searches = false;
        for (java.lang.reflect.Field field : listing.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> fieldType = field.getType();
            if (!Map.class.isAssignableFrom(fieldType)
                && !net.minecraft.tags.TagKey.class.isAssignableFrom(fieldType)) {
                continue;
            }
            Object value;
            try {
                if (!field.trySetAccessible()) {
                    return new ListingShape(null, true);
                }
                value = field.get(listing);
            } catch (IllegalAccessException e) {
                return new ListingShape(null, true);
            }
            if (value instanceof net.minecraft.tags.TagKey<?> tag
                && tag.registry().equals(Registries.STRUCTURE)) {
                searches = true;
            } else if (value instanceof Map<?, ?> map && !map.isEmpty()
                && map.keySet().stream().allMatch(VillagerType.class::isInstance)) {
                byType = map;
            }
        }
        return new ListingShape(byType, searches);
    }

    private static String typeId(VillagerType type) {
        return BuiltInRegistries.VILLAGER_TYPE.getKey(type).toString();
    }

    // Rolls a variant twice; a listing whose offer depends on the random source differs between the
    // rolls and is marked, so a caller can tell a fixed trade from one that varies per villager.
    private static TradeOffer materializeListing(
        String professionId,
        int level,
        boolean experimental,
        ListingVariant variant,
        RegistryOps<JsonElement> ops
    ) {
        MerchantOffer first = listingOffer(variant.listing(), variant.trader(), SAMPLE_SEED_A);
        if (first == null) {
            return null;
        }
        MerchantOffer second = listingOffer(variant.listing(), variant.trader(), SAMPLE_SEED_B);
        return rolledOffer(variant.listing().getClass().getName(), first, second, ops)
            .attached(professionId, level, experimental, variant.villagerType());
    }

    private static MerchantOffer listingOffer(VillagerTrades.ItemListing listing, Entity context, long seed) {
        try {
            return listing.getOffer(context, RandomSource.create(seed));
        } catch (RuntimeException e) {
            // A listing that cannot be materialized without more world context keeps its
            // declaration; that is reported, not guessed.
            return null;
        }
    }

    // A listing that could not be turned into an offer, reported by what it declares.
    private static TradeOffer describeListing(
        String professionId,
        int level,
        boolean experimental,
        ListingVariant variant,
        RegistryOps<JsonElement> ops
    ) {
        JsonElement declaration = JsonCodec.snapshotObject(variant.listing(), SNAPSHOT_DEPTH, ops);
        return new TradeOffer(
            professionId, Integer.valueOf(level), experimental, variant.listing().getClass().getName(), null,
            null, null, null, 0, 0, 0.0F, false, variant.villagerType(), null
        ).described(jsonOf(declaration));
    }
    //? }
}

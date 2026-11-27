package net.minetake.mcinsight.trade;

import com.google.gson.JsonElement;

/**
 * One trade as the version's own trade system produced it.
 *
 * <p>This is the seam between "where the trades come from", which differs per version, and "how a
 * trade is reported", which does not: {@link TradeCollector} answers the first, this type carries the
 * answer, and {@link TradeCollector#tradeOf} renders it.
 *
 * <p>A rolled offer is built with the profession and level it belongs to still missing, because the
 * rolling code does not know them; {@link #attached} fills them in once. {@code villagerType} stays
 * null when the trade is the same for every type. {@code declaration} is set instead of the stacks
 * when the trade could only be described rather than rolled. {@code tradeId} names the trade in the
 * datapack registry, which only the versions that keep trades in a registry have. {@code level} is
 * null for trades the game does not level, which is how the wandering trader works.
 */
record TradeOffer(
    String professionId,
    Integer level,
    boolean experimental,
    String listingClass,
    String tradeId,
    StackRef costA,
    StackRef costB,
    StackRef result,
    int maxUses,
    int xp,
    float priceMultiplier,
    boolean sampled,
    String villagerType,
    JsonElement declaration
) {

    /** The same trade, attached to the profession, level and type it was rolled for. */
    TradeOffer attached(String professionId, int level, boolean experimental, String villagerType) {
        return new TradeOffer(
            professionId, Integer.valueOf(level), experimental, listingClass, tradeId,
            costA, costB, result, maxUses, xp, priceMultiplier, sampled, villagerType, declaration
        );
    }

    /**
     * The same trade, with the registry entry it is defined by and the type it is limited to.
     *
     * <p>{@code level} is nullable because the wandering trader's offers are not levelled; passing
     * one through must not unbox a level the game never assigned.
     */
    TradeOffer attached(String professionId, Integer level, boolean experimental, String tradeId, String villagerType) {
        return new TradeOffer(
            professionId, level, experimental, listingClass, tradeId,
            costA, costB, result, maxUses, xp, priceMultiplier, sampled, villagerType, declaration
        );
    }

    /** The same trade, described by what it declares rather than by what it rolls. */
    TradeOffer described(JsonElement declaration) {
        return new TradeOffer(
            professionId, level, experimental, listingClass, tradeId,
            null, null, null, 0, 0, 0.0F, false, villagerType, declaration
        );
    }
}

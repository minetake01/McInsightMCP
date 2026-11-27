package net.minetake.mcinsight.trade;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * A trade the game does not level — the wandering trader's — keeps its absent level when it is
 * attached to the profession it is offered under, instead of being unboxed into a level of zero.
 */
class TradeOfferTest {

    @Test
    void aLevelLessTradeStaysLevelLessWhenAttached() {
        TradeOffer wandering = new TradeOffer(
            "minecraft:wandering_trader", null, false, "VillagerTrade", "mcinsight:test_trade",
            null, null, null, 0, 0, 0.0F, false, null, null
        );

        TradeOffer attached = wandering.attached(
            "minecraft:wandering_trader", null, false, "mcinsight:test_trade", "minecraft:plains"
        );

        assertNull(attached.level());
    }
}

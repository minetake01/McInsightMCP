package net.minetake.mcinsight;

import net.minetake.mcinsight.advancement.AdvancementCollector;
import net.minetake.mcinsight.api.InsightDispatcher;
import net.minetake.mcinsight.api.InsightEndpoint;
import net.minetake.mcinsight.datamaps.DataMapQuery;
import net.minetake.mcinsight.guides.GuideQuery;
import net.minetake.mcinsight.loot.LootQuery;
import net.minetake.mcinsight.player.PlayerStateCollector;
import net.minetake.mcinsight.recipe.RecipeCollector;
import net.minetake.mcinsight.recipe.RecipeImageQuery;
import net.minetake.mcinsight.reflect.ClassIntrospector;
import net.minetake.mcinsight.registry.EntryInspector;
import net.minetake.mcinsight.registry.RegistrySearch;
import net.minetake.mcinsight.registry.TagQuery;
import net.minetake.mcinsight.status.StatusHandler;
import net.minetake.mcinsight.trade.TradeCollector;
import net.minetake.mcinsight.world.AreaScanner;
import net.minetake.mcinsight.world.BlockEntityPart;
import net.minetake.mcinsight.world.TargetCollector;
import net.minetake.mcinsight.worldgen.WorldgenQuery;

/**
 * Binds every endpoint to its handler.
 *
 * <p>One table ties the endpoint contract to the implementation; a new endpoint cannot exist
 * without a handler, and a bridgeable endpoint cannot be advertised without being registered.
 */
public final class InsightHandlers {

    private InsightHandlers() {}

    public static void registerAll() {
        InsightDispatcher.register(InsightEndpoint.STATUS, StatusHandler::status);
        InsightDispatcher.register(InsightEndpoint.MODS, StatusHandler::mods);
        InsightDispatcher.register(InsightEndpoint.METRICS, (ctx, params) -> StatusHandler.metrics(ctx));
        InsightDispatcher.register(InsightEndpoint.PLAYER, PlayerStateCollector::handle);

        InsightDispatcher.register(InsightEndpoint.LOOKING_AT, TargetCollector::lookingAt);
        InsightDispatcher.register(InsightEndpoint.SCAN, AreaScanner::scan);

        InsightDispatcher.register(InsightEndpoint.REGISTRY_SEARCH, RegistrySearch::search);
        InsightDispatcher.register(InsightEndpoint.REGISTRY_INSPECT, EntryInspector::inspect);
        InsightDispatcher.register(InsightEndpoint.REGISTRY_TAG, TagQuery::tag);

        InsightDispatcher.register(InsightEndpoint.RECIPES_TYPES, RecipeCollector::listTypes);
        InsightDispatcher.register(InsightEndpoint.RECIPES_QUERY, RecipeCollector::query);
        InsightDispatcher.register(InsightEndpoint.RECIPES_RENDER, RecipeImageQuery::render);

        InsightDispatcher.register(InsightEndpoint.GUIDES_LIST, GuideQuery::list);
        InsightDispatcher.register(InsightEndpoint.GUIDES_SEARCH, GuideQuery::search);
        InsightDispatcher.register(InsightEndpoint.GUIDES_PAGE, GuideQuery::page);

        InsightDispatcher.register(InsightEndpoint.WORLDGEN_SUMMARY, WorldgenQuery::summary);
        InsightDispatcher.register(InsightEndpoint.WORLDGEN_QUERY, WorldgenQuery::query);
        InsightDispatcher.register(InsightEndpoint.LOOT_QUERY, LootQuery::query);
        InsightDispatcher.register(InsightEndpoint.DATAMAPS_QUERY, DataMapQuery::query);
        InsightDispatcher.register(InsightEndpoint.TRADES_QUERY, TradeCollector::query);
        InsightDispatcher.register(InsightEndpoint.ADVANCEMENTS_QUERY, AdvancementCollector::query);

        InsightDispatcher.register(InsightEndpoint.INTROSPECT_CLASS, ClassIntrospector::inspect);

        InsightDispatcher.register(InsightEndpoint.SERVER_LOOKING_AT_BLOCK_ENTITY, BlockEntityPart::serverLookingAtBlockEntity);
        InsightDispatcher.register(InsightEndpoint.SERVER_SCAN_BLOCK_ENTITIES, BlockEntityPart::serverBlockEntities);
    }
}

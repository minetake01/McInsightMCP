import { McpServer } from '@modelcontextprotocol/server';
import { McHttpClient } from './client/mcClient.js';
import { JarInspector } from './decompiler/jarInspector.js';
import { registerCodeTools } from './tools/codeTools.js';
import { registerGuideTools } from './tools/guideTools.js';
import { registerMechanicsTools } from './tools/mechanicsTools.js';
import { registerRecipeImageTools } from './tools/recipeImageTools.js';
import { registerRegistryTools } from './tools/registryTools.js';
import { registerWorldStateTools } from './tools/worldStateTools.js';
import { readPackageVersion } from './version.js';

export interface ServerDeps {
  mcClient: McHttpClient;
  jarInspector: JarInspector;
  defaultModsDir?: string;
  defaultGameDir?: string;
}

const SERVER_INSTRUCTIONS = [
  'McInsight inspects a live modded Minecraft session (and local mod JARs/pack scripts).',
  '- Tools that take an item or target ID require an exact ResourceLocation (e.g. "minecraft:stone") or tag (e.g. "#minecraft:planks"). Resolve display names with mc_search_registry first.',
  '- Prefer runtime tools (mc_query_item_flow, mc_query_recipes, mc_guides, mc_query_world_data, mc_inspect) for gameplay questions; use mc_inspect_code when you need mod implementation details or pack scripts.',
  '- Use mc_render_recipe to show a visual recipe diagram to the user, and mc_query_recipes or mc_query_item_flow to inspect recipe data as text.',
].join('\n');

export function createMcInsightServer(deps: ServerDeps): McpServer {
  const server = new McpServer(
    {
      name: 'mcinsight',
      version: readPackageVersion(),
    },
    {
      instructions: SERVER_INSTRUCTIONS,
    },
  );

  registerWorldStateTools(server, deps.mcClient);
  registerRegistryTools(server, deps.mcClient);
  registerMechanicsTools(server, deps.mcClient);
  registerRecipeImageTools(server, deps.mcClient);
  registerGuideTools(server, deps.mcClient);
  registerCodeTools(server, {
    mcClient: deps.mcClient,
    jarInspector: deps.jarInspector,
    defaultModsDir: deps.defaultModsDir,
    defaultGameDir: deps.defaultGameDir,
  });

  return server;
}

import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { limitBounds } from '../api/endpoints.js';
import { toFailure, ToolError } from '../api/result.js';
import type { McHttpClient } from '../client/mcClient.js';
import { fetchRecipeImages } from '../data/recipeImages.js';
import { jsonText } from './helpers.js';
import { imageResult } from './result.js';

const RENDER_LIMITS = limitBounds('recipes_render');

const ITEM_ID_DESCRIPTION =
  'Exact item ResourceLocation (e.g. "minecraft:crafting_table") or tag (e.g. "#minecraft:planks"). Required unless recipeId is specified.';

const RENDER_RECIPE_SCHEMA = z.object({
  item: z.string().min(1).optional().describe(ITEM_ID_DESCRIPTION),
  recipeId: z.string().min(1).optional().describe('Exact viewer recipe ID to render (optional alternative to item)'),
  role: z
    .enum(['any', 'input', 'output', 'catalyst', 'workstation'])
    .optional()
    .default('output')
    .describe('Role the item must occupy in the recipe (output answers "how is this made")'),
  type: z
    .string()
    .optional()
    .describe('Viewer category UID to restrict the search (e.g. "minecraft:crafting")'),
  source: z
    .string()
    .optional()
    .default('viewer')
    .describe('viewer uses all active viewers that can draw; a viewer name (e.g. jei, emi, rei) picks one'),
  limit: z
    .number()
    .int()
    .min(1)
    .max(RENDER_LIMITS.maxLimit)
    .optional()
    .describe(`Number of recipes to render (default ${RENDER_LIMITS.defaultLimit}, max ${RENDER_LIMITS.maxLimit})`),
  offset: z.number().int().min(0).optional().default(0).describe('Number of matching recipes to skip'),
});

export function registerRecipeImageTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_render_recipe',
    {
      title: 'Render Recipe Diagram',
      description:
        'Render a recipe as an image using an active in-game recipe viewer (JEI, EMI, or REI), including modded machine GUIs. Use when the user should see the recipe visually; use mc_query_recipes for text-only recipe data.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: RENDER_RECIPE_SCHEMA,
    },
    async ({ item, recipeId, role, type, source, limit, offset }) => {
      if (!item && !recipeId) {
        return {
          ...jsonText(toFailure(new ToolError('INVALID_PARAMETER', 'Provide at least one of item or recipeId.'))),
          isError: true,
        };
      }
      return fetchRecipeImages(mcClient, { item, recipeId, role, type, source, limit, offset }).then(
        (response) => {
          // The text block repeats the answer without the picture bytes; the images carry those.
          const textImages = response.images.map(({ png: _png, ...rest }) => rest);
          const { images, ...summary } = response;
          return imageResult(
            { ...summary, images: textImages },
            images.map((image) => ({ data: image.png, mimeType: 'image/png' })),
          );
        },
        (error: unknown) => ({
          ...jsonText(toFailure(error)),
          isError: true,
        }),
      );
    },
  );
}

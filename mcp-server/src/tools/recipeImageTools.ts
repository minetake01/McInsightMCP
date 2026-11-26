import type { McpServer } from '@modelcontextprotocol/server';
import * as z from 'zod/v4';
import { limitBounds } from '../api/endpoints.js';
import { toFailure } from '../api/result.js';
import type { McHttpClient } from '../client/mcClient.js';
import { fetchRecipeImages } from '../data/recipeImages.js';
import { jsonText } from './helpers.js';
import { imageResult } from './result.js';

const RENDER_LIMITS = limitBounds('recipes_render');

const ITEM_ID_DESCRIPTION =
  'Exact item ResourceLocation such as "minecraft:crafting_table", or a tag such as "#minecraft:planks". Display names and substrings are rejected with INVALID_ID; resolve a name with mc_search_registry first.';

const RENDER_RECIPE_SCHEMA = z.object({
  item: z.string().min(1).describe(ITEM_ID_DESCRIPTION),
  role: z
    .enum(['any', 'input', 'output', 'catalyst', 'workstation'])
    .optional()
    .default('output')
    .describe('Which part of a recipe the item must occupy; output answers "how is this made"'),
  type: z
    .string()
    .optional()
    .describe('Viewer category UID to restrict the search, e.g. minecraft:crafting or a mod machine category'),
  source: z
    .string()
    .optional()
    .default('viewer')
    .describe('viewer means every active viewer that can draw; a name such as jei, emi, or rei picks one'),
  limit: z
    .number()
    .int()
    .min(1)
    .max(RENDER_LIMITS.maxLimit)
    .optional()
    .describe(
      `How many recipes to draw; omitted means ${RENDER_LIMITS.defaultLimit}. Maximum ${RENDER_LIMITS.maxLimit}. Each image is returned as its own MCP image block.`,
    ),
  offset: z.number().int().min(0).optional().default(0).describe('Skip this many matching recipes'),
  scale: z
    .number()
    .int()
    .min(1)
    .max(3)
    .optional()
    .default(2)
    .describe('Pixel scale of the rendered image; 2 is legible in a chat client, 3 is larger'),
});

export function registerRecipeImageTools(server: McpServer, mcClient: McHttpClient): void {
  server.registerTool(
    'mc_render_recipe',
    {
      title: 'Render Recipe Diagram',
      description:
        'Draws a recipe the way its in-game recipe viewer draws it and returns the image: the machine or crafting GUI, its slots, and its arrows, exactly as JEI, EMI, or REI renders that category. Use this when the user should see the recipe rather than read slot lists. Works for any category the viewer can draw, including modded machines. Requires a running Minecraft client with a recipe viewer; the same answer as text is returned beside the images, and mc_query_recipes lists recipes without drawing them.',
      annotations: { readOnlyHint: true, idempotentHint: true },
      inputSchema: RENDER_RECIPE_SCHEMA,
    },
    async ({ item, role, type, source, limit, offset, scale }) =>
      fetchRecipeImages(mcClient, { item, role, type, source, limit, offset, scale }).then(
        (response) => {
          // The text block repeats the answer without the picture bytes; the images carry those.
          const textImages = response.images.map(({ png: _png, ...rest }) => rest);
          const { images, ...summary } = response;
          return imageResult(
            { ...summary, images: textImages },
            images.map((image) => ({ data: image.png, mimeType: 'image/png' })),
          );
        },
        (error: unknown) => jsonText(toFailure(error)),
      ),
  );
}

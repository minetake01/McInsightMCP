import { toFailure } from '../api/result.js';
import { jsonText } from './helpers.js';

/**
 * The one result envelope every tool call goes through.
 *
 * <p>Two invariants live here rather than in each tool:
 *
 * <ul>
 *   <li>a successful call always answers a JSON object that carries `ok: true` — the mod's own
 *       responses already declare it, and these tools compose responses, so the boundary guarantees
 *       the property instead of trusting every branch to repeat it;
 *   <li>a failure always answers `{ok: false, code, error}` with the mod's `InsightError` code when
 *       the mod answered, so a caller never has to parse an English sentence to know what happened.
 * </ul>
 *
 * <p>Nothing that reaches this boundary is silently discarded: a thrown error is the only failure
 * channel, and a successful return is by definition complete.
 */
export function toolResult(handler: () => Promise<object>) {
  return handler().then(
    (value) => {
      const payload = { ...(value as Record<string, unknown>), ok: (value as { ok?: unknown }).ok ?? true };
      const failed = payload.ok === false;
      return {
        ...jsonText(payload),
        ...(failed ? { isError: true } : {}),
      };
    },
    (error: unknown) => ({
      ...jsonText(toFailure(error)),
      isError: true,
    }),
  );
}

/**
 * A result that carries images beside its JSON text.
 *
 * <p>The text block keeps the same envelope as every other tool, so a client that does not render
 * images still receives the answer; the image blocks are the same answer in its visual form, never
 * a second source of truth. Image data is never repeated in the text block, and a failure is built
 * by the same {@link toolResult} boundary, so `ok`/`isError` are decided once for every tool.
 */
export interface ImageToolAnswer {
  summary: object;
  images: readonly { data: string; mimeType: string }[];
}

export function imageToolResult(handler: () => Promise<ImageToolAnswer>) {
  return handler().then(
    ({ summary, images }) => ({
      content: [
        { type: 'text' as const, text: JSON.stringify({ ...(summary as Record<string, unknown>), ok: true }) },
        ...images.map((image) => ({
          type: 'image' as const,
          data: image.data,
          mimeType: image.mimeType,
        })),
      ],
    }),
    (error: unknown) => ({
      ...jsonText(toFailure(error)),
      isError: true,
    }),
  );
}

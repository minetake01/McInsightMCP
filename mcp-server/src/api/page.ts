/**
 * The one place the page envelope is carried over when a data kind rewrites its array.
 *
 * <p>`Page.java` on the mod side is the single declaration, and `PageEnvelope` mirrors it here.
 * A data kind that parses rows still answers the same envelope, so the fields are copied in exactly
 * one function: a kind says only "this is the array key and these are its rows", which is what makes
 * `totalKnown` / `total` / `returned` / `hasMore` behave identically everywhere.
 */

import type { PageEnvelope } from './types.js';

export function carryPage<Extra extends Record<string, unknown>>(
  page: PageEnvelope,
  extra: Extra,
): PageEnvelope & Extra {
  return {
    ok: true,
    offset: page.offset,
    totalKnown: page.totalKnown,
    ...(page.totalKnown && page.total !== undefined ? { total: page.total } : {}),
    returned: page.returned,
    hasMore: page.hasMore,
    ...extra,
  };
}

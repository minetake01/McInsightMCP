import { describe, expect, it } from 'vitest';
import { carryPage } from '../src/api/page.js';

/**
 * The one page-envelope carrier: a data kind says which rows it rewrote, and the envelope fields
 * travel unchanged — in particular an uncounted page never grows a `total`.
 */
describe('carryPage', () => {
  it('carries an uncounted page without inventing a total', () => {
    const page = carryPage(
      { ok: true, offset: 20, totalKnown: false, returned: 2, hasMore: true },
      { entries: ['a', 'b'] },
    );
    expect(page).toEqual({
      ok: true,
      offset: 20,
      totalKnown: false,
      returned: 2,
      hasMore: true,
      entries: ['a', 'b'],
    });
    expect('total' in page).toBe(false);
  });

  it('carries a counted page total', () => {
    const page = carryPage(
      { ok: true, offset: 0, totalKnown: true, total: 41, returned: 2, hasMore: true },
      { entries: ['a', 'b'] },
    );
    expect(page.total).toBe(41);
    expect(page.totalKnown).toBe(true);
  });
});

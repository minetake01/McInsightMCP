/**
 * The code-introspection data kind.
 *
 * <p>The mod answers `introspect_class` with the loaded class's declared shape; the JAR, bytecode,
 * and decompiler tiers live beside this module because they are the primary evidence it explains.
 * Keeping the endpoint here means the code tool names an endpoint through a data module like every
 * other tool, instead of reaching into the world-state kind it does not belong to.
 */

import { requestEndpoint } from '../api/endpoints.js';
import type { ClassIntrospectResponse } from '../api/types.js';
import type { McHttpClient } from '../client/mcClient.js';

export async function fetchClassIntrospection(
  client: McHttpClient,
  name: string,
): Promise<ClassIntrospectResponse> {
  return requestEndpoint(client, 'introspect_class', { name });
}

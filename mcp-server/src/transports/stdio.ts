import { serveStdio } from '@modelcontextprotocol/server/stdio';
import { ServerDeps, createMcInsightServer } from '../server.js';

export async function startStdioServer(deps: ServerDeps): Promise<void> {
  await serveStdio(() => createMcInsightServer(deps));
  console.error('[McInsightMCP] Running on stdio transport (MCP 2026-07-28)');
}

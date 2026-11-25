import { McpServer } from '@modelcontextprotocol/server';
import { McHttpClient } from './client/mcClient.js';
import { JarInspector } from './decompiler/jarInspector.js';
import { registerCodeTools } from './tools/codeTools.js';
import { registerGuideTools } from './tools/guideTools.js';
import { registerMechanicsTools } from './tools/mechanicsTools.js';
import { registerRegistryTools } from './tools/registryTools.js';
import { registerWorldStateTools } from './tools/worldStateTools.js';
import { readPackageVersion } from './version.js';

export interface ServerDeps {
  mcClient: McHttpClient;
  jarInspector: JarInspector;
  defaultModsDir?: string;
  defaultGameDir?: string;
}

export function createMcInsightServer(deps: ServerDeps): McpServer {
  const server = new McpServer({
    name: 'mcinsight',
    version: readPackageVersion(),
  });

  registerWorldStateTools(server, deps.mcClient);
  registerRegistryTools(server, deps.mcClient);
  registerMechanicsTools(server, deps.mcClient);
  registerGuideTools(server, deps.mcClient);
  registerCodeTools(server, {
    mcClient: deps.mcClient,
    jarInspector: deps.jarInspector,
    defaultModsDir: deps.defaultModsDir,
    defaultGameDir: deps.defaultGameDir,
  });

  return server;
}

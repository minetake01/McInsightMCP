import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';

const repoRoot = path.resolve(import.meta.dirname, '..');
const require = createRequire(path.join(repoRoot, 'mcp-server', 'package.json'));
const AdmZip = require('adm-zip');

const PERF_RUN_DIR = path.join(repoRoot, 'mod', 'neoforge', 'runs', 'client');
const DATAPACK_DIR = path.join(PERF_RUN_DIR, 'saves', 'perf_world', 'datapacks', 'perf_modpack_sim');
const FTB_QUESTS_DIR = path.join(PERF_RUN_DIR, 'config', 'ftbquests', 'quests', 'chapters');
const FIXTURE_MODS_DIR = path.join(repoRoot, 'bench', 'fixtures', 'mods');

const RECIPE_COUNT = 5000;
const LOOT_COUNT = 150;
const QUEST_COUNT = 80;
const JAR_COUNT = 60;

async function ensureDir(dir) {
  await fs.mkdir(dir, { recursive: true });
}

async function setupClientConfigs() {
  await ensureDir(path.join(PERF_RUN_DIR, 'saves', 'perf_world'));
}

async function setupDatapack() {
  const recipeDir = path.join(DATAPACK_DIR, 'data', 'perfsim', 'recipe');
  const lootDir = path.join(DATAPACK_DIR, 'data', 'perfsim', 'loot_table', 'blocks');
  await ensureDir(recipeDir);
  await ensureDir(lootDir);

  await fs.writeFile(
    path.join(DATAPACK_DIR, 'pack.mcmeta'),
    JSON.stringify({ pack: { pack_format: 48, description: 'McInsight Perf Simulation Modpack' } }, null, 2),
    'utf-8',
  );

  const items = [
    'minecraft:iron_ingot',
    'minecraft:gold_ingot',
    'minecraft:diamond',
    'minecraft:redstone',
    'minecraft:copper_ingot',
    'minecraft:emerald',
    'minecraft:lapis_lazuli',
    'minecraft:coal',
    'minecraft:quartz',
    'minecraft:amethyst_shard',
  ];
  const inputs = [
    'minecraft:cobblestone',
    'minecraft:iron_nugget',
    'minecraft:gold_nugget',
    'minecraft:raw_iron',
    'minecraft:raw_gold',
    'minecraft:raw_copper',
    'minecraft:sand',
    'minecraft:gravel',
    'minecraft:clay_ball',
    'minecraft:oak_planks',
  ];

  const batchSize = 250;
  for (let i = 0; i < RECIPE_COUNT; i += batchSize) {
    const writes = [];
    for (let j = i; j < Math.min(i + batchSize, RECIPE_COUNT); j++) {
      const outItem = items[j % items.length];
      const inItem = inputs[(j * 3) % inputs.length];
      const secondIn = inputs[(j * 7 + 1) % inputs.length];
      let recipe;
      switch (j % 4) {
        case 0:
          recipe = {
            type: 'minecraft:crafting_shapeless',
            ingredients: [{ item: inItem }, { item: secondIn }],
            result: { id: outItem, count: 1 + (j % 3) },
          };
          break;
        case 1:
          recipe = {
            type: 'minecraft:smelting',
            ingredient: { item: inItem },
            result: { id: outItem },
            experience: 0.5,
            cookingtime: 200,
          };
          break;
        case 2:
          recipe = {
            type: 'minecraft:blasting',
            ingredient: { item: inItem },
            result: { id: outItem },
            experience: 0.7,
            cookingtime: 100,
          };
          break;
        default:
          recipe = {
            type: 'minecraft:stonecutting',
            ingredient: { item: inItem },
            result: { id: outItem, count: 2 },
          };
          break;
      }
      writes.push(
        fs.writeFile(path.join(recipeDir, `sim_recipe_${String(j).padStart(4, '0')}.json`), JSON.stringify(recipe), 'utf-8'),
      );
    }
    await Promise.all(writes);
  }

  const lootWrites = [];
  for (let i = 0; i < LOOT_COUNT; i++) {
    const outItem = items[i % items.length];
    const loot = {
      type: 'minecraft:block',
      pools: [
        {
          rolls: 1.0,
          bonus_rolls: 0.0,
          entries: [
            {
              type: 'minecraft:item',
              name: outItem,
              functions: [
                {
                  function: 'minecraft:set_count',
                  count: { min: 1.0, max: 3.0, type: 'minecraft:uniform' },
                },
              ],
            },
          ],
          conditions: [{ condition: 'minecraft:survives_explosion' }],
        },
      ],
    };
    lootWrites.push(
      fs.writeFile(path.join(lootDir, `sim_block_${String(i).padStart(3, '0')}.json`), JSON.stringify(loot), 'utf-8'),
    );
  }
  await Promise.all(lootWrites);
}

async function setupFtbQuests() {
  await ensureDir(FTB_QUESTS_DIR);
  const filler =
    'This chapter covers automated resource processing, kinetic stress distribution, fluid piping, and multiblock energy storage. ';
  const writes = [];
  for (let i = 0; i < QUEST_COUNT; i++) {
    const keyword =
      i % 5 === 0
        ? 'multiblock_reactor'
        : i % 3 === 0
          ? 'perf_keyword_alpha'
          : 'iron_ingot';
    const bodyLines = [];
    for (let k = 0; k < 45; k++) {
      bodyLines.push(`        "Line ${k}: ${filler}Target token: ${keyword} stage=${i}."`);
    }
    const snbt = `{
  id: "PERF_CH_${String(i).padStart(3, '0')}"
  group: "perf_simulation"
  order_index: ${i}
  filename: "chapter_${String(i).padStart(3, '0')}"
  title: "Perf Simulation Chapter ${i} (${keyword})"
  quests: [
    {
      id: "PERF_Q_${i}_A"
      x: ${i}.0d
      y: 0.0d
      description: [
${bodyLines.join('\n')}
      ]
      tasks: [{ id: "T_${i}", type: "item", item: "minecraft:iron_ingot" }]
    }
  ]
}
`;
    writes.push(
      fs.writeFile(path.join(FTB_QUESTS_DIR, `chapter_${String(i).padStart(3, '0')}.snbt`), snbt, 'utf-8'),
    );
  }
  await Promise.all(writes);
}

async function setupFixtureJars() {
  await ensureDir(FIXTURE_MODS_DIR);
  const dummyClassBytes = Buffer.from([
    0xca, 0xfe, 0xba, 0xbe, 0x00, 0x00, 0x00, 0x41, 0x00, 0x01, 0x00, 0x00,
  ]);
  for (let i = 0; i < JAR_COUNT; i++) {
    const modId = `simmod_${String(i).padStart(2, '0')}`;
    const jarPath = path.join(FIXTURE_MODS_DIR, `${modId}-1.21.1-1.0.${i}.jar`);
    const zip = new AdmZip();
    zip.addFile(
      'META-INF/neoforge.mods.toml',
      Buffer.from(`modLoader="javafml"\n[[mods]]\nmodId="${modId}"\nversion="1.0.${i}"\ndisplayName="Sim Mod ${i}"\n`),
    );
    for (let c = 0; c < 60; c++) {
      const clsName = c === 0 && i === 42 ? 'TargetMachineBlock' : `Component${c}`;
      zip.addFile(`com/example/${modId}/block/${clsName}.class`, dummyClassBytes);
    }
    for (let r = 0; r < 20; r++) {
      zip.addFile(
        `data/${modId}/recipe/machine_${r}.json`,
        Buffer.from(JSON.stringify({ type: `${modId}:process`, index: r, output: 'minecraft:iron_ingot' })),
      );
    }
    zip.writeZip(jarPath);
  }
}

async function main() {
  const t0 = performance.now();
  await Promise.all([
    setupClientConfigs(),
    setupDatapack(),
    setupFtbQuests(),
    setupFixtureJars(),
  ]);
  const elapsed = (performance.now() - t0).toFixed(1);
  console.log(
    `[setup-perf-env] Ready in ${elapsed}ms: ${RECIPE_COUNT} recipes, ${LOOT_COUNT} loot tables, ${QUEST_COUNT} FTB quests, ${JAR_COUNT} fixture JARs.`,
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});

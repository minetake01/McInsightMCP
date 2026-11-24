# McInsightMCP

[English](README.md) | **日本語**

Minecraft 1.21.1 (NeoForge + Fabric) 向けの汎用・読み取り専用ゲームシステム解析MODと、MCP `2026-07-28` 準拠（ステートレス Streamable HTTP / stdio 両対応）の TypeScript MCP サーバーです。

## 設計思想

- **3層のハイブリッド解釈アーキテクチャ**:
  1. **Minecraft標準機構パーサー**: バニラのクラフト/精錬レシピ、ワールド生成（鉱脈・高度・バイオーム・構造物）、ルートテーブル（ドロップ/確率/シルクタッチ条件）、レジストリ・タグを機械的に構造化します。
  2. **バージョン指定・組み込み主要MODパーサー層（`mcp-server/src/parsers/modParsers/`）**: Create (`6.x`)、Mekanism (`10.7.x`)、AE2 (`19.x`)、Oritech (`1.x`)、Farmer's Delight (`1.x`)、Ars Nouveau (`5.x`)、TofuCraftReload (`12.x`) などの主要MODについては、`semver` で実行中のMODバージョンと一致する場合のみ機械的に構造化します（バージョン不一致時は誤解釈を防ぎ、生JSONへ自動フォールバックします）。
  3. **AI推論基盤＋軽量ナレッジ層**: 上記で扱えないMODや独自ギミック・マルチブロックについては、KubeJS改変込みの生レシピJSON・JEI情報・BlockEntity NBT・ガイド/クエスト（Patchouli / GuideME / FTB Quests / Create Ponder / 言語ファイル）・MOD JAR内のデータや Vineflower / `javap` によるJavaクラス逆コンパイル結果をそのままAIに渡し、AIが仕様を理解します。一度理解した仕様は自由形式のMarkdownノート（`knowledge/notes/*.md`）やサンドボックスJS抽出器（`knowledge/extractors/*.json`）として再利用します。

## 主な特徴

- **他MODハード依存ゼロ（MC 1.21.1 マルチローダー）**: NeoForge / Fabric のどちらでも単体動作し、JEI・Patchouli・GuideME・FTB Quests・Create Ponder 等は存在時のみ自動で連携します。
- **読み取り専用・クライアント単体起動を基本**: マルチプレイサーバー未導入でもレシピ・インベントリ・視線先・ガイドを参照でき、シングルやサーバー両側導入時は完全な BlockEntity NBT やルートテーブルも取得します。
- **MCP `2026-07-28` 準拠**: `@modelcontextprotocol/server` v2.4 / `@modelcontextprotocol/node` v2.1 によるステートレス Streamable HTTP（`127.0.0.1` 限定 + Host/Origin 検証）および `stdio` に対応。

## ディレクトリ構成

```
McInsightMCP/
├── docs/
│   └── ARCHITECTURE.md     # HTTP API仕様・3層ハイブリッド設計・ナレッジ形式
├── mod/                    # MC 1.21.1 Multiloader Gradleプロジェクト (common / neoforge / fabric)
└── mcp-server/             # TypeScript MCPサーバー (2026-07-28) + knowledge/
```

## ビルド・起動方法

### 1. Minecraft MOD のビルド（JDK 21）
```bash
cd mod
./gradlew :neoforge:build :fabric:build
```
生成物:
- `mod/neoforge/build/libs/mcinsight-neoforge-1.21.1-1.0.0.jar`
- `mod/fabric/build/libs/mcinsight-fabric-1.21.1-1.0.0.jar`

### 2. MCP サーバーのビルド・テスト・起動（Node.js >= 20, pnpm）
```bash
cd mcp-server
pnpm install
pnpm typecheck
pnpm build
pnpm test

# Streamable HTTP (MCP 2026-07-28, http://127.0.0.1:3876/mcp)
node dist/index.js --http --port 3876

# stdio トランスポート
node dist/index.js --stdio
```

### 3. 主な MCP ツール
- **ゲーム状態・レジストリ**: `mc_status`, `mc_player_state`, `mc_looking_at`, `mc_scan_area`, `mc_search_registry`, `mc_inspect_item`, `mc_inspect_tag`
- **レシピ・生成・ドロップ・取引・進捗**: `mc_list_recipe_types`, `mc_query_recipes`, `mc_query_worldgen`, `mc_query_loot`, `mc_query_datamaps`, `mc_query_trades`, `mc_query_advancements`
- **ガイド・クエスト**: `mc_search_guides`, `mc_get_guide_page`
- **解析・逆コンパイル**: `mc_inspect_class`, `mc_search_jars`（パス検索・テキスト全文検索・`.class` 定数プール文字列検索対応）, `mc_read_jar_resource`, `mc_decompile_class`
- **ナレッジ蓄積**: `mc_save_knowledge_note`, `mc_search_knowledge`, `mc_get_knowledge_note`, `mc_register_extractor`, `mc_test_extractor`, `mc_list_knowledge`

## ライセンス

[MIT](LICENSE)

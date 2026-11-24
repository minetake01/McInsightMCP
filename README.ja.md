# McInsightMCP

[English](README.md) | **日本語**

Minecraft 1.21.1 (NeoForge + Fabric) 向けの汎用・読み取り専用・ステートレスゲームシステム解析MODと、MCP `2026-07-28` 準拠（ステートレス Streamable HTTP / stdio 両対応）の TypeScript MCP サーバーです。

## 設計思想

- **2層の機械的パーサー＋一次証拠／バイトコード解析**:
  1. **Minecraft標準機構パーサー（`mcp-server/src/parsers/nativeParsers.ts`）**: バニラのクラフト/精錬レシピ、ワールド生成（鉱脈・高度・バイオーム気候/スポーン・構造物・Biome Modifier）、ルートテーブル＆Global Loot Modifier、レジストリ・タグを機械的に構造化します。
  2. **バージョン指定・組み込み主要MODパーサー層（`mcp-server/src/parsers/modParsers/`）**: Create (`6.x`)、Mekanism (`10.7.x`)、AE2 (`19.x`)、Oritech (`1.x`)、Farmer's Delight (`1.x`)、Ars Nouveau (`5.x`)、TofuCraftReload (`12.x`) などの主要MODについては、`semver` で実行中のMODバージョンと一致する場合のみ機械的に構造化します（バージョン不一致時は推測せず `parserVersionMismatch` を返して生JSONへフォールバックします）。
  3. **一次証拠・バイトコード逆参照・逆コンパイル**: 上記で扱えないMODやインワールドギミック・マルチブロックについては、KubeJS改変込みの生レシピJSON・JEI情報・BlockEntity NBT・各種ガイド/クエスト（Patchouli / GuideME / FTB Quests / Create Ponder / Oracle Index / 言語ファイル）・ランタイムのブロック挙動メタデータ（`isRandomlyTicking` / `stateProperties`）に加え、MOD JAR内の `<clinit>` 登録フィールド逆引き（`trace_id`）や Vineflower / `javap` によるJavaクラス逆コンパイル結果をそのままAIに提供します。

## 主な特徴

- **他MODハード依存ゼロ（MC 1.21.1 マルチローダー）**: NeoForge / Fabric のどちらでも単体動作し、JEI・Patchouli・GuideME・FTB Quests・Create Ponder 等は存在時のみ自動で連携します。
- **完全読み取り専用・ステートレス**: ゲーム内を改変せず、MCPサーバー側にも永続状態を保持しません。クライアント単体導入でもレシピ・インベントリ・視線先・ガイドを参照でき、シングルや両側導入時は完全な BlockEntity NBT やルートテーブルも取得します。
- **MCP `2026-07-28` 準拠**: `@modelcontextprotocol/server` v2.4 / `@modelcontextprotocol/node` v2.1 によるステートレス Streamable HTTP（`127.0.0.1` 限定 + Host/Origin 検証）および `stdio` に対応。

## ディレクトリ構成

```
McInsightMCP/
├── docs/
│   └── ARCHITECTURE.md     # HTTP API仕様・パーサー設計・ツール仕様
├── mod/                    # MC 1.21.1 Multiloader Gradleプロジェクト (common / neoforge / fabric)
└── mcp-server/             # TypeScript MCPサーバー (2026-07-28)
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

### 3. MCP ツール一覧（意味論ベースの 11 ツール）
- **ワールド・プレイヤー状態**:
  - `mc_status`: MOD接続状態・ワールド情報・導入MOD一覧
  - `mc_player_state`: 現在地・バイオーム・周辺環境（`biomeBaseTemperature`, `canSeeSky`, 光量, 降水）・ステータス・インベントリ・開いている画面
  - `mc_observe_world`: 視線先の詳細取得（`looking_at`）および周囲スキャン（`area`）
- **レジストリ・仕様確認**:
  - `mc_search_registry`: アイテム・ブロック・流体・エンティティ・タグの検索
  - `mc_inspect`: アイテム／ブロックの詳細（`isRandomlyTicking`, `stateProperties`, `destroySpeed`, `jeiInformation`）またはタグメンバー一覧
- **入手経路・システム解析**:
  - `mc_query_item_flow`: アイテムの入手方法・用途をレシピ・ルートテーブル・取引・Data Map・JEI情報から一括横断検索
  - `mc_query_recipes`: レシピタイプ一覧・組み込みパーサー一覧の取得、または条件指定のレシピ検索（`includeRaw` 対応）
  - `mc_query_world_data`: ワールド生成（バイオーム温度フィルタ・`includeRaw` 対応）・ルートテーブル・取引・NeoForge Data Maps の検索
  - `mc_query_advancements`: 進捗定義・報酬・プレイヤー達成状況の検索
- **ドキュメント・コード解析**:
  - `mc_guides`: Patchouli / GuideME / FTB Quests / Ponder / Oracle Index / 言語ファイルの検索・一覧・閲覧
  - `mc_inspect_code`: 登録IDから静的フィールド定義・参照クラスを辿るバイトコード追跡（`trace_id`）、JAR/パック検索（`search`）、JAR内リソース閲覧（`read_resource`）、Vineflower/`javap` 逆コンパイル（`decompile`）、JVMクラスリフレクション（`reflect`）

## ライセンス

[MIT](LICENSE)

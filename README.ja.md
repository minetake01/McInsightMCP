# McInsightMCP

[English](README.md) | **日本語**

[![CI](https://github.com/minetake01/McInsightMCP/actions/workflows/ci.yml/badge.svg)](https://github.com/minetake01/McInsightMCP/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/minetake01/McInsightMCP?include_prereleases&label=release)](https://github.com/minetake01/McInsightMCP/releases)
[![Status: Beta](https://img.shields.io/badge/Status-Beta-yellow.svg)](CHANGELOG.md)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-NeoForge%20%7C%20Fabric-62B47A.svg)](https://github.com/minetake01/McInsightMCP/releases)

今プレイしている Minecraft のワールド情報をそのまま AI アシスタントに伝えて質問できる MOD と MCP サーバーです。

McInsightMCP を使うと、Claude Desktop・Cursor・Claude Code・VS Code などの MCP 対応 AI アシスタントを、起動中の Minecraft に直接接続できます。古い Wiki に頼って AI が推測するのではなく、導入中の Modpack（KubeJS やデータパックによる変更込み）のレシピ、視線先のブロックや機械、所持インベントリ、ゲーム内ガイドブックやクエストを AI がリアルタイムに確認して回答します。

> [!NOTE]
> ベータ版（`0.1.0-beta.2`）: McInsightMCP は現在ベータ版として公開しています。`1.0.0` に向けて MCP ツールの仕様、出力形式、設定項目が変更される場合があります。フィードバックや不具合のご報告は [GitHub Issues](https://github.com/minetake01/McInsightMCP/issues) までお寄せください。現在の開発版には、応答形式の破壊的な内部変更（未リリース）も含まれます。ページのフィールド、エラーコード、レシピビューアのメタデータが同時に変わっており、11 個のツール名は変更ありません。

---

## 主な特徴

- ゲーム内で有効なレシピ、鉱石の生成高度やバイオーム、ルートテーブル、村人取引、進捗、KubeJS やデータパックによる改変を直接参照して回答します。
- ワールドの改変、ブロックの設置や破壊、プレイヤーの操作、コマンドの実行は一切行いません。
- 通信は PC 内部（`127.0.0.1`）に限定しており、外部への送信や画面キャプチャは行いません。
- マルチプレイで自分のクライアントだけに導入した状態でも、レシピ、インベントリ、視線先の対象、ガイド類を確認できます。シングルプレイの場合、またはサーバー側にも McInsight が導入されている場合は、サーバー側のデータ（ワールド生成、ルートテーブル、Data Maps、進捗、ブロックエンティティの中身）も自動で取得します。
- 各種ガイド MOD が導入されていれば自動で連携し、ドキュメントが見当たらない未知の MOD 仕様でも MOD リソースやクラス実装から仕組みを調べられます。

> [!TIP]
> **データの取得元は応答に含まれません。** シングルプレイ、McInsight を導入したサーバー、導入していないサーバーのいずれでも応答の形は同じで、取得元によって分岐する必要はありません。サーバー側のデータを実際に読めない場合（例: McInsight を導入していないマルチプレイサーバー）は、該当するエンドポイントが明示的なエラー（`SERVER_MOD_REQUIRED` または `DATA_UNAVAILABLE`）を返し、複合的な応答ではその部分だけが `partial: true` と `missing: [{part, code, error}]` で不足として示されます。黙って情報を減らすことはありません。

---

## AI に聞けることの例

- *「この Modpack で `mekanism:atomic_alloy` を作る手順と、今手持ちのインベントリで足りていない素材を教えて」*
- *「今見ている機械は何のブロックで、なぜ動いていないのか状態を確認して」*
- *「この環境のオスミウム鉱石やプラチナ鉱石はどの高さ・バイオームで生成される？」*
- *「Patchouli / FTB Quests / Create Ponder / GuideME の中からこの素材の自動化方法を調べて」*
- *「Wiki に載っていない MOD アイテムの仕組みを、MOD 本体から読み解いて説明して」*

---

## 動作要件と対応環境

| 項目 | 要件 |
|---|---|
| Minecraft | 現在は `1.21.1`（Java 21）に対応（対応バージョン一覧は [Releases](https://github.com/minetake01/McInsightMCP/releases) を参照） |
| NeoForge | 単体で動作（必須の前提 MOD なし） |
| Fabric | [Fabric API](https://modrinth.com/mod/fabric-api) が必須 |
| MCP サーバー | [Node.js](https://nodejs.org/) `22.12` 以上 |

### 自動連携するオプション MOD
- JEI / EMI / REI（レシピカテゴリ、触媒ブロック、アイテム・流体等の説明ページ）
- ゲーム内ガイド・クエスト: Patchouli / GuideME / FTB Quests / Create Ponder / Oracle Index / 言語ファイル

### 主要 MOD 向けレシピ整形
どの MOD のレシピも生データで取得できますが、Create・Mekanism・Applied Energistics 2・Oritech・Farmer's Delight・Ars Nouveau・TofuCraftReload については見やすく整理された形式で返却します。

---

## 導入手順

### 手順 1: Minecraft MOD を導入する

1. [GitHub Releases](https://github.com/minetake01/McInsightMCP/releases) からお使いの Minecraft バージョン・ローダー用の JAR をダウンロードします。
   - NeoForge: `mcinsight-neoforge-<mc_version>-<version>.jar`
   - Fabric: `mcinsight-fabric-<mc_version>-<version>.jar`。Fabric 環境では `mods/` に Fabric API も配置してください。
2. ダウンロードした JAR を Minecraft インスタンスの `mods/` フォルダに配置します。
3. Minecraft を起動し、ワールドに入ります。

### 手順 2: AI クライアントに接続する

#### 方法 A: `stdio` 接続（推奨）

Claude Desktop・Cursor・Claude Code・VS Code 向けです。AI クライアントの MCP 設定に以下を追加してください。

```json
{
  "mcpServers": {
    "mcinsight": {
      "command": "npx",
      "args": ["-y", "mcinsight-mcp-server@beta", "--stdio"]
    }
  }
}
```

pnpm 環境では `"command": "pnpx"`、`"args": ["mcinsight-mcp-server@beta", "--stdio"]` も使えます。

#### 方法 B: Streamable HTTP 接続

ローカルで MCP サーバーを起動します。

```bash
npx -y mcinsight-mcp-server@beta --http --port 3876
# pnpm を使う場合:
pnpx mcinsight-mcp-server@beta --http --port 3876
```

AI クライアント側で接続先 URL に `http://127.0.0.1:3876/mcp` を指定してください。

#### 手動セットアップ（GitHub Releases / オフライン環境）

[GitHub Releases](https://github.com/minetake01/McInsightMCP/releases) から `mcinsight-mcp-server-<version>` アーカイブをダウンロードして展開し、フォルダ内で `pnpm install --prod`（または `npm install --omit=dev`）を実行したうえで `node /path/to/mcinsight-mcp-server/dist/index.js --stdio` を指定することもできます。ソースからビルドする場合は、下の「ソースからのビルド」を参照してください。

> ヒント: MCP サーバーは起動中の McInsight MOD（`127.0.0.1` のポート `25580..25599`）を自動で検出するため、通常はポート指定不要です。MOD 側の `port` や `portRangeSize` を変更した場合は、自動検出は既定の範囲しか調べないため、`--mc-url`（または `MCINSIGHT_URL`）で接続先を明示してください。

---

## オプション設定

### Minecraft MOD 設定（`config/mcinsight.json`）

初回起動時に自動生成されます。

| キー | 初期値 | 説明 |
|---|---|---|
| `enabled` | `true` | ローカル HTTP API の有効/無効 |
| `port` | `25580` | 待ち受けを開始するローカルポート番号（`1024..65535`） |
| `portRangeSize` | `20` | ポート使用中に試行する連続ポート数（`1..50`） |
| `authToken` | `""` | API リクエストに必須とする任意の Bearer トークン |
| `maxScanRadius` | `16` | 周囲スキャンの最大半径（`1..32`） |
| `maxSyncBlockRadius` | `16` | マルチプレイ同期で送信するブロックエンティティ中身の最大半径（`1..16`） |
| `maxRecipeResults` | `100` | 1 回の検索で返すレシピ上限数（`1..500`） |

### MCP サーバーの CLI フラグ / 環境変数

| CLI フラグ | 環境変数 | 用途 |
|---|---|---|
| `--stdio` | — | stdio モードで起動（デフォルト） |
| `--http` | — | ローカル Streamable HTTP サーバーとして起動 |
| `--port <port>` | `MCP_PORT` | HTTP サーバーの待受ポート（デフォルト `3876`） |
| `--mc-url <url>` | `MCINSIGHT_URL` | MOD の接続先 URL を明示指定（自動検出をスキップ） |
| `--token <token>` | `MCINSIGHT_TOKEN` | `mcinsight.json` の `authToken` に合わせるトークン |
| `--mods-dir <path>` | `MCINSIGHT_MODS_DIR` | オフラインで MOD JAR を調べる際の `mods` フォルダパス |
| `--game-dir <path>` | `MCINSIGHT_GAME_DIR` | オフラインで KubeJS / FTB Quests を調べる際のゲームフォルダパス |
| `--tools-dir <path>` | `MCINSIGHT_TOOLS_DIR` | 逆コンパイル用ツール（Vineflower）の保存先フォルダ |

---

## 提供される AI ツール（11 種類）

- ワールド・プレイヤー状態: `mc_status`, `mc_player_state`, `mc_observe_world`（視線先のブロック/流体/エンティティや周囲スキャン）
- アイテム・ブロック: `mc_search_registry`, `mc_inspect`（アイテム・ブロック・流体・エンティティ性質・タグ・JEI/EMI/REI 情報）
- 入手・レシピ・進行: `mc_query_item_flow`, `mc_query_recipes`, `mc_query_world_data`（鉱脈/バイオーム・ルートテーブル・取引・NeoForge Data Maps）, `mc_query_advancements`
- ガイド・MOD 解析: `mc_guides`（Patchouli / GuideME / FTB Quests / Ponder / Oracle Index / 言語ファイル）, `mc_inspect_code`（リソース・Jar-in-Jar・スクリプト検索と Java クラス解析）

アイテムを指定するツールは、**完全な ID**（`namespace:path`）または**タグ**（`#namespace:tag`）だけを受け付けます。表示名や部分一致では検索されないため、表示名から ID を特定する場合は先に `mc_search_registry` を使ってください。

---

## ソースからのビルド

MOD のビルドには JDK 21 が必要です（Gradle 自体は JDK 25 で動き、見つからなければ自動でダウンロードされます）。MCP サーバーのビルドには Node.js 22.12 以上と pnpm が必要です。

```bash
# 1. 共通ロジックのテストと NeoForge / Fabric MOD のビルド
cd mod
./gradlew :common:test :neoforge:build :fabric:build
# 生成物:
#   mod/neoforge/build/libs/mcinsight-neoforge-<mc_version>-0.1.0-beta.2.jar
#   mod/fabric/build/libs/mcinsight-fabric-<mc_version>-0.1.0-beta.2.jar

# 2. MCP サーバーのビルド・テスト
cd ../mcp-server
pnpm install
pnpm typecheck
pnpm build
pnpm test
```

---

## ドキュメント・関連リンク

- [アーキテクチャ・プロトコル仕様](docs/ARCHITECTURE.md): エンドポイントとエラーの契約、応答の形、通信方式、パッケージ構成、スレッド設計の詳細
- [コントリビュートガイド](CONTRIBUTING.md): 開発環境の構築、テスト方法、MOD パーサーの追加手順
- [セキュリティポリシー](SECURITY.md): 読み取り専用設計、ローカル通信の仕組み、脆弱性報告窓口
- [変更履歴](CHANGELOG.md): バージョンごとの変更点
- [ライセンス (MIT)](LICENSE)

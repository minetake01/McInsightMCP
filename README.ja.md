# McInsightMCP

[English](README.md) | **日本語**

今プレイしている Minecraft のワールド情報をそのまま AI アシスタントに伝えて質問できる MOD と MCP サーバーです。

McInsightMCP を使うと、Claude Desktop、Cursor、Claude Code、VS Code などの MCP 対応 AI アシスタントを起動中の Minecraft に接続できます。古い Wiki に頼って推測するのではなく、導入中の Modpack（KubeJS やデータパックによる変更を含む）のレシピ、視線先のブロックや機械、インベントリ、ゲーム内ガイドブックやクエストを AI がリアルタイムに確認して回答します。

> **ベータ版:** McInsightMCP は現在ベータ版のため、`1.0.0` に向けて設定項目や内部のツール動作が変わる場合があります。不具合の報告やご意見は [GitHub Issues](https://github.com/minetake01/McInsightMCP/issues) までお寄せください。

## AI に聞けることの例

- 「この Modpack で `mekanism:atomic_alloy` を作る手順と、今のインベントリで足りていない素材を教えて」
- 「`minecraft:oak_planks` のクラフトレシピを画像で見せて」
- 「今見ている機械は何のブロックで、なぜ動いていないのか確認して」
- 「この環境のオスミウム鉱石やプラチナ鉱石はどの高さとバイオームで生成される？」
- 「Patchouli、FTB Quests、Create Ponder、GuideME からこの素材の自動化方法を調べて」
- 「Wiki に載っていない MOD アイテムの仕組みを、MOD 本体から読み解いて説明して」

## 主な特徴

- 導入中の Modpack に合わせて、有効なレシピ、KubeJS やデータパックによる変更、鉱石の生成条件、ルートテーブル、村人取引、進捗をゲームから直接参照します。
- JEI、EMI、REI が画面に描いているそのままの形（機械や作業台の GUI、スロット、矢印）でレシピを画像として生成し、AI が説明ではなく図として提示できます（JEI は全対応バージョン、EMI と REI は `1.21.1`）。
- 読み取り専用で動作し、ブロックの設置や破壊、ワールドの変更、プレイヤーの移動、コマンドの実行は一切行いません。
- 通信は自分の PC 内（`127.0.0.1`）だけに限定しており、外部サーバーへの送信や画面キャプチャは行いません。レシピ画像はレイアウトからオフスクリーンで描画するもので、画面の一部を切り取ることはありません。
- マルチプレイで自分のクライアントだけに導入した場合でも、レシピ、インベントリ、視線先の対象、ガイド類を確認できます。シングルプレイ、またはサーバー側にも McInsight が導入されている場合は、鉱石などのワールド生成データ、ルートテーブル、村人取引、進捗、NeoForge Data Maps、チェストや機械などブロックエンティティの中身といったサーバー側データも取得します。
- どの Modpack でも動作し、対応するレシピビューアやガイド MOD と自動で連携します。ドキュメントが見当たらない MOD でも、MOD のリソースやクラスを調べて仕組みを確認できます。

## 動作要件と対応環境

| 項目 | 要件 |
|---|---|
| Minecraft | `1.21.1`（Java 21）、`26.1.2`、`26.3`（Java 25） |
| NeoForge | 単体で動作（必須の前提 MOD なし） |
| Fabric | [Fabric API](https://modrinth.com/mod/fabric-api) が必須 |
| MCP サーバー | [Node.js](https://nodejs.org/) `22.12` 以上 |

### 自動連携するオプション MOD

- レシピビューア: JEI、REI、EMI（EMI は `1.21.1` のみ対応）
- ゲーム内ガイドとクエスト: Patchouli、GuideME、FTB Quests、Create Ponder、Oracle Index、MOD の言語ファイル
- レシピの整形出力: すべての MOD のレシピを生データで取得できるほか、Create、Mekanism、Applied Energistics 2、Oritech、Farmer's Delight、Ars Nouveau、TofuCraftReload については構造化して見やすく出力します

## 導入手順

### 1. Minecraft MOD を導入する

1. お使いの Minecraft バージョンとローダーに合う MOD の JAR をダウンロードします。Fabric 環境では [Fabric API](https://modrinth.com/mod/fabric-api) も入れてください。
2. ダウンロードした JAR を Minecraft インスタンスの `mods/` フォルダに配置します。
3. Minecraft を起動してワールドに入ります。

### 2. AI クライアントに接続する

Claude Desktop、Cursor、Claude Code、VS Code などの MCP 設定に以下を追加してください。

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

通常は MCP サーバーが `127.0.0.1` のポート `25580`〜`25599` から起動中の MOD を自動で見つけるため、ポートの指定は不要です。

`stdio` ではなく Streamable HTTP を使う場合は、ローカルでサーバーを起動して `http://127.0.0.1:3876/mcp` に接続してください。

```bash
npx -y mcinsight-mcp-server@beta --http --port 3876
```

オフライン環境向けに、MCP サーバーをまとめたアーカイブも [GitHub Releases](https://github.com/minetake01/McInsightMCP/releases) で配布しています。

## オプション設定

通常は設定を変更せずにそのまま使えます。

- Minecraft MOD の設定は初回起動時に `config/mcinsight.json` として生成されます。ローカル API の有効・無効（`enabled`）、開始ポート（`port`、初期値 `25580`）、Bearer トークン（`authToken`）、周辺スキャン・ブロックエンティティ同期・レシピ結果数の上限を変更できます。
- MCP サーバーの起動引数や環境変数では、MOD のポートを変更した場合の `--mc-url`（`MCINSIGHT_URL`）、`authToken` に合わせる `--token`（`MCINSIGHT_TOKEN`）、Minecraft を起動せずに MOD ファイルを調べるときに使う `--mods-dir` と `--game-dir`（`MCINSIGHT_MODS_DIR` / `MCINSIGHT_GAME_DIR`）を指定できます。

すべての設定項目、上限値、CLI フラグは [アーキテクチャ・設定仕様](docs/ARCHITECTURE.md#10-configuration-and-limits) を参照してください。

## 関連リンク

- [アーキテクチャ・設定仕様](docs/ARCHITECTURE.md)
- [コントリビュート・ビルド手順](CONTRIBUTING.md)
- [セキュリティポリシー](SECURITY.md)
- [変更履歴](CHANGELOG.md)
- [ライセンス (MIT)](LICENSE)

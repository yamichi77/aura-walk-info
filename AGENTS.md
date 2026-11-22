# AGENTS.md

XREAL AURA（Android XR の有線XRグラス）向けの徒歩HUDアプリ。歩いた距離・歩行時間・マップ・現在の住所を、視界の正面を避けた位置に表示する。歩数は扱わない（パックに歩数センサーの記載がないため。2026-11-22 決定）。

## 進め方

- ユーザーとのやり取りは日本語で行う
- 計画は [docs/PLAN.md](docs/PLAN.md) を正とし、フェーズ順に実装する。各フェーズの終わりに受け入れ条件を確認して報告する
- **PLAN.md §2（安全設計）の配置ルールや閾値を変える場合は、ユーザーに確認してから変える**（中央クリアゾーン ±15°、高速移動時の非表示など）
- ドキュメントに書かれていない挙動（回転の符号、入力イベントの種類など）は推測で決めない。エミュレータや実機で確かめ、`docs/device-capabilities.md` に記録する

## 技術

- Kotlin / Jetpack Compose for XR / ARCore for Jetpack XR、単一モジュール MVVM、手動 DI
- 地図は Google Maps（maps-compose、Map ID なし）。ほかの地図に変える場合はユーザーに確認する
- 依存バージョンは `gradle/libs.versions.toml` で固定する。実験的 API には `@OptIn` を明記する

## 秘密情報と個人情報（public リポジトリ）

- このリポジトリは GitHub で public に公開する前提で管理する
- API キーや署名鍵は `local.properties` などの .gitignore 済みのファイルに置き、コミットしない
- テスト用の位置データ（GPX など）は合成データか公共の場所のものを使う。自宅周辺の実際の歩行ログはコミットしない
- コミット前に betterleaks が pre-commit フックで走る。クローンした直後に `pre-commit install` を実行する。`--no-verify` で回避しない
- 検出されたら秘密を取り除いてからコミットし直す。誤検出のときだけ、その行に `betterleaks:allow` を付け、理由をコメントで残す
- push と PR では GitHub Actions（`.github/workflows/secret-scan.yml`）が全履歴を検査する
- betterleaks を上げるときは、`.pre-commit-config.yaml` の `rev`、ワークフローの `BETTERLEAKS_VERSION` と `BETTERLEAKS_SHA256`、ローカルのバイナリの3つを揃える。v2 ではステージ済み差分の検査コマンドが変わる（`git --pre-commit --staged` が廃止され、`stdin --git-diff` 方式になる）ので注意

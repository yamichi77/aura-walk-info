# T26: 空間の印が見えない原因を切り分ける診断モード（2026-11-24 実機確認）

まず [README.md](README.md) の共通ルールを読むこと。T1〜T25 は main に入っている。

## 背景（T20 の2回目の実機確認）

`bash scripts/device-check.sh --only spatial` は全項目 PASS（ログ上は `SHOW mark=TURN distanceM=39 elevationDeg=7.4..8.4` など）だったが、グラスをかけたユーザーには、曲がり角の矢印・目的地の光の柱・信号の柱の**どれも見えなかった**。

そのときの端末のログに、システム側の次の警告が毎秒出ていた。

```
W/com.android.systemui: XRInputBufferConsumer.cpp(469) Tried to read anchor with uuid [0x...] but was unable to find it
```

空間の印は、すべて `Anchor.create` → `AnchorSpace.create` にぶら下げた `MeshEntity` / `PanelEntity` で描いている（`spatial/SpatialMarks.kt`、`SpatialMarkMesh.kt`、`SignalTotemPanel.kt`）。アンカーがシステムの描画から見えず、何も描かれていない疑いがある。

また、曲がり角の矢印の線の太さは 0.035m で、39m 先では視角 0.05°（グラスの約 1.6 画素）しかなく、仮に描かれても見えない。

## 作るもの

### 1. 診断モード（デバッグビルド、`--es mode spatialtest`）

起動したときの頭の位置と向きを基準に、**現実の空間に固定した**次の試しの表示を出す。HUD の表示（頭に追従するパネル）も、今までどおり出してよい。

| 記号 | 置き方 | 位置（起動時の頭から） | 中身 |
|---|---|---|---|
| A-mesh | `Anchor` + `AnchorSpace` に `MeshEntity`（今と同じ仕組み） | 正面 4m・左 1.2m・上 0.8m | 1m 四方の正方形の輪郭（線の太さ 0.05m）、色 `#7FDBFF`、不透明度 100% |
| A-panel | `Anchor` + `AnchorSpace` に `PanelEntity` | 正面 4m・左 1.2m・上 0.0m | 文字 `A`（大きく、`#E0F7FF`）を書いた 0.6m 四方のパネル |
| B-mesh | アンカーを使わず、Full Space の空間（`ActivitySpace` など、頭に追従しない空間）に直接 `MeshEntity` | 正面 4m・右 1.2m・上 0.8m | A-mesh と同じ形 |
| B-panel | アンカーを使わず、同じ空間に直接 `PanelEntity` | 正面 4m・右 1.2m・上 0.0m | 文字 `B` のパネル |

- ARCore の頭の姿勢（perception space）から、B の空間の座標への変換は、SceneCore の空間どうしの変換の API（`transformPoseTo` など）を、`gradle/libs.versions.toml` のバージョンのライブラリで調べて使う
- ログ（`AuraSpatialTest`）に、各表示について次を 1 秒ごとに出す: 作成の成否、親の状態（`AnchorSpace.State` など）、`isEnabled`、頭から見た向き（左右・上下の角度）と距離。座標の値そのものは出さない
- 端末に echo で「今から○秒、正面を見て A と B のどちらが見えるか確かめてください」「終わり」と出す `scripts/device-check.sh --only spatialtest` を足す（`bash -n` で文法だけ確かめる）

### 2. 線の太さの下限（今の空間の印）

- 曲がり角の矢印・目的地の光の柱の線の太さを、**頭からの距離に応じて太くし、視角 0.15° 以上**（40m 先で約 0.1m）を保つ。矢印の大きさも、視角 3° を下回らないよう距離に応じて大きくする。値は定数1か所にまとめる
- 仰角 5° 以上の部分だけを描く規則（PLAN.md §2.5）は変えない

## 確認手順

1. `./gradlew test assembleDebug` が通る（JDK 21）
2. 単体テスト: 線の太さと矢印の大きさの距離に応じた計算（10m・40m・100m で視角の下限を満たす）
3. `bash -n scripts/device-check.sh`
4. 既存のテストがすべて通る
5. **実機（adb）は使わない**。オーケストレーターが受け入れ中なので、main のワークツリーには触らない

## 受け入れ条件

- 上の確認がすべて通る
- UI.md と PLAN.md を変更していない
- 最終報告に、B の空間に直接置く方法として使った API と、その空間が「頭に追従せず、現実の空間に固定される」とする根拠（ドキュメントやライブラリのソース）を書く

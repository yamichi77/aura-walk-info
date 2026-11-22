# T5: ミニマップ（UI.md §3.2）

まず [README.md](README.md) の共通ルールを読むこと。T1〜T4 と T6a は完了している。T6b（安全機能の仕上げ）が別の作業ツリーで同時に進んでいる。

実機の制約: グラスを装着していないと表示がスリープし、空間 UI も地図の描画も確かめられない（T2・T4 の知見）。実機での地図の確認は `scripts/device-check.sh` に地図の項目（関数）として足し、オーケストレーターがユーザーの装着中に実行する。非装着でも、2D の画面に同じ `MiniMapPanel` を出して確かめられる範囲は確かめる。合成ルートの再生は `adb shell am broadcast -a io.github.yamichi77.aurawalkinfo.REPLAY -p io.github.yamichi77.aurawalkinfo --es scenario walk_stop --ef speed 4`（T6a）。

## 目的

右クラスタの仮の円と矢印を、Google Maps のダークスタイルのミニマップに置き換える。描画方法は T2 の結果（`docs/device-capabilities.md`）に従う。

## 並行作業との分担

このタスクで触るのは次だけ。

- `ui/hud/MiniMapPanel.kt`（新規）
- 地図スタイルの JSON（例: `app/src/main/res/raw/map_style_dark.json`、新規）
- `HudScene` の中で右クラスタを呼んでいる1か所（仮の円を `MiniMapPanel` に差し替える）
- 必要なら `HudLayoutConfig` に地図の定数（ズームなど）を足す

`MainActivity`、`AppContainer`、`session/`、`safety/`、左クラスタは触らない。

## 作るもの

- maps-compose の `GoogleMap` を円で切り抜いて表示する。直径は T4 の見た目の仕様（歩行中 8°、停止中 12°、約300ms で滑らかに変わる）に従う
- 地図の設定
  - Map ID は使わない。`MapStyleOptions` で JSON スタイルを当てる
  - ズーム 17.5（17〜18 の範囲）、チルト 0
  - ジェスチャはすべて無効。ズームボタン、コンパス、現在地ボタン、マップツールバー、屋内のフロア切替もすべて無効。地図の現在地レイヤー（青い点）は使わない
  - カメラの中心は `WalkSessionState` の最新位置、向きは `mapBearingDeg`（`null` なら 0 = north-up）。位置や向きが変わったら、カメラを 0.5〜1秒かけて動かす（急に飛ばさない）
- ダークスタイル（UI.md §3.2）: 地面・建物・公園など面はすべて `#000000`、細い道路 `#3A3A3A`、太い道路（幹線・高速）`#808080`、水域は暗い青（例: `#0B1A2E`）。POI とラベル（文字とアイコン）はすべて非表示
- 現在地: 円の中心に上向きの矢印（`#7FDBFF`）を Compose で重ねて描く
- 円の枠: 1px 相当、`#E0F7FF` の不透明度40%（T4 の仮の円と同じ）
- 測位なしのとき: 地図を不透明度30%、矢印を `#888780` にする
- まだ一度も位置が無いとき: 地図は出さず、円の枠と矢印だけにする
- T2 で通常モードの描画に問題があると分かっていたら、ライトモード（`liteMode(true)`）にする。ライトモードでは回転できないので north-up になる。その場合は最終報告にはっきり書く

## 地図の自己診断（デバッグビルドのみ）

- `--ez selfcheck true` のとき、`onMapLoaded` が呼ばれたか、`GoogleMap.snapshot()` の画像の「黒以外の画素の割合」と「明るい画素（輝度 50% 以上）の割合」、カメラの bearing を logcat（タグ `AuraMap`）に出す
  - 判定: `onMapLoaded` が呼ばれる、黒以外の画素が 1% 以上（道路が描かれている）、明るい画素が 25% 以下（明るい面が小さい）。満たせば `PASS`
- snapshot の画像を files ディレクトリに保存し、`build/device-shots/` に取り出して自分で開いて確かめる（**この画像はコミットしない**）

## API キー

- ユーザーが `local.properties` の `MAPS_API_KEY` に本物のキーを入れているはず。入っていない（ダミーのまま）場合は、ビルド時の値で判別して最終報告に書き、自己診断は「キーなし」として記録する。キーを探したり、自分で書いたりしない

## 確認手順（すべて自動で行う）

1. `./gradlew test` と `./gradlew assembleDebug` が通る
2. 実機で `--es fakeState walking` と `stopped` と `nofix`、`--ez selfcheck true` で起動し、`AuraMap` の自己診断が `PASS` になることを確かめる
3. T6a の再生（`walk_stop`）で、曲がったときにカメラの bearing が約 90° 変わることを logcat（`AuraMap`）で確かめる。非装着で地図が描画されない場合は、`mapBearingDeg` からカメラに渡す値の変化までを確かめ、残りを `scripts/device-check.sh` の項目にする
4. snapshot の画像を取り出して、道路の線が見え、背景が黒いことを確かめる

## 受け入れ条件

- 自己診断が `PASS`（地図が描画され、道路の線があり、明るい面積が小さい）
- 右クラスタの左端が右16°より外側（T4 の配置の自己診断も `PASS` のまま）
- 状態ごとの直径と薄さが T4 の見た目の仕様どおり

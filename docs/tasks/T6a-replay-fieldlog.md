# T6a: 合成ルートの再生とフィールドログ

まず [README.md](README.md) の共通ルールを読むこと。T1〜T3 は完了している。T4（HUD）が別の作業ツリーで同時に進んでいる。

## 目的

1. 屋外を歩かずに、実機上で状態の切替（歩行中 / 停止中 / 高速移動 / 測位なし）を再現できるようにする
2. 散歩の後に閾値を調整するための記録（フィールドログ）を残す（HANDOFF.md §6）
3. 状態の切替を、合成ルートを通したエンドツーエンドのテストで確かめる

## 並行作業との分担

T4 は `MainActivity.kt`、`ui/`、`settings/`、新規の `AuraWalkApplication.kt`、マニフェスト本体を触る。このタスクでは**それらを触らない**。
このタスクで触るのは次だけ。

- `AppContainer.kt`
- `session/`、`location/`、`safety/`（必要な最小限の変更）
- `diagnostics/replay/`、`diagnostics/fieldlog/`（新規）
- `app/src/test/` 配下
- `.gitignore`、`docs/field-test.md`（新規）

T4 は `AuraWalkApplication` の中で `AppContainer(context)` を作り、`walkSessionRepository.start(scope)` を呼ぶ予定。`AppContainer` のコンストラクタの形（`AppContainer(context: Context)`）と、`walkSessionRepository` / `safetyNotice` の公開名は変えない。

## T2 で分かった実機の制約

- グラスを装着していないと表示がスリープし、アプリは休止する（`xr_doff`）。**再生のロジックは、表示の有無に関係なく動くようにする**（`AppContainer` の中で完結させ、アクティビティに依存しない）
- adb の作法: インストールは `adb install -r --no-streaming`、起動は `adb shell am start -S -n io.github.yamichi77.aurawalkinfo/.MainActivity`

## 作るもの

### 1. 合成ルートの再生（デバッグビルドのみ）

- 起動方法は**ブロードキャスト**にする（アクティビティに依存しないため）
  - `adb shell am broadcast -a io.github.yamichi77.aurawalkinfo.REPLAY -p io.github.yamichi77.aurawalkinfo --es scenario <名前> --ef speed <倍率>`
  - `--es scenario stop` で本物の位置に戻す
  - レシーバーは、デバッグビルドのときだけ `AppContainer` の中で `registerReceiver(..., RECEIVER_EXPORTED)` で動的に登録する（マニフェストは触らない）。デバッグかどうかは `ApplicationInfo.FLAG_DEBUGGABLE` で判定する
- 再生を始めたらセッションをリセットし（距離・時間を0から）、`LocationRepository` の流れを合成ルートに切り替える（`flatMapLatest` などで本物と切り替えられるようにする）
- T3 の `FakeLocationRepository` と合成データの生成関数（`SyntheticLocations.kt`）は test ソースセットにある。再生とテストの両方で使えるよう、main ソースセットの `diagnostics/replay/` に移し、テストからもそれを使う
- 早送り（`speed` > 1）では、`LocationSample` の時刻と `Clock`（測位なしのタイムアウト判定や歩行時間の計算に使う）を同じ倍率で進める。再生用の `Clock` を差し込めるようにする
- 起点は公共の場所（例: 東京駅丸の内駅前広場付近）。実在の歩行ログは使わない
- 住所は再生中は Geocoder を呼ばずに、ダミーの住所（例: `千代田区丸の内1丁目` / 2行なら `東京都千代田区` `丸の内1丁目`）を返す
- シナリオ（1秒に1点。精度は通常 5m 程度で少し揺らす。速度と方位も付ける）

| 名前 | 内容 |
|---|---|
| `walk_stop` | 1.3 m/s で60秒歩く → 30秒立ち止まる（精度 5〜15m の揺らぎ、速度 0〜0.2 m/s） → 1.3 m/s で60秒、途中で一度 90° 曲がる |
| `fast` | 1.3 m/s で30秒歩く → 6 m/s で30秒 → 1.3 m/s で30秒 |
| `nofix` | 1.3 m/s で30秒歩く → 15秒間位置が来ない → 精度 40m の点が10秒 → 1.3 m/s で30秒 |

- 再生中は1秒ごとに logcat（タグ `AuraSession`）へ出す: 経過秒（再生の時間で）、状態、3秒平均の速度、精度、距離、歩行時間、地図の方位。状態が変わったときは `TRANSITION <前> -> <後> at <秒>` の行も出す。**緯度経度と住所は出さない**

### 2. フィールドログ

- デバッグビルドで、本物の位置で動いているときは常に記録する（再生中は記録しない）
- 保存先はアプリの files ディレクトリの `fieldlog/` 配下。セッションごとに1つの CSV（ファイル名は開始日時）
- 1秒に1行: 経過秒、状態、3秒平均の速度、その測位の精度、その秒に足した距離、その測位を採用したか捨てたか（捨てた理由: 精度不足 / 移動量不足 / 歩行中でない）、測位プロバイダ（fused / gps）
  - 必要なら `DistanceTracker` と `MotionClassifier` に、判定理由を外から読める小さな情報（最後の判定結果など）を足してよい。判定ロジック自体は変えない
- **緯度経度と住所は記録しない**
- 書き込みは IO ディスパッチャで、まとめて（例: 10行ごと）flush する。電池と発熱に配慮する
- `docs/field-test.md`（新規）の冒頭に、取り出し方を書く（`adb exec-out run-as io.github.yamichi77.aurawalkinfo ls files/fieldlog` と `cat`。PowerShell 7.4 未満ではリダイレクトでバイト列が壊れることがあるので Git Bash を使う、など）
- `.gitignore` に、取り出したログの置き場所 `fieldlogs/` を追加する

### 3. エンドツーエンドのテスト（JVM）

上の3つのシナリオを、再生用の `LocationRepository` → `WalkSessionRepository` まで通して、仮想時間で流すテストを書く。

- `fast`: 6 m/s になってから 5秒（±1秒）で `FAST` になる。6 m/s の区間の距離と歩行時間は増えない（`FAST` になるまでの5秒の分は除く）。1.3 m/s に戻って5秒（±1秒）で `FAST` を抜ける
- `walk_stop`: 立ち止まって5秒（±1秒）で `STOPPED` になり、停止中は距離も歩行時間も増えない。歩き出すと `WALKING` に戻る。曲がった後、`mapBearingDeg` が約 90° 変わる
- `nofix`: 位置が来なくなって10秒（±1秒）で `NO_FIX`、精度 40m の間も `NO_FIX`。その間、距離と歩行時間は増えない
- フィールドログの1行に、緯度経度らしき値（小数点以下が多い ±90 / ±180 の範囲の数）が含まれないこと

## 確認手順

1. `./gradlew test` と `./gradlew assembleDebug` が通る
2. 実機で（装着していなくてよい）アプリを `am start -S` で起動してから、3つのシナリオを `--ef speed 4` で再生し、`adb logcat -s AuraSession` の `TRANSITION` の行と値が上のテストと一致することを確かめる。結果の要約（状態が切り替わった秒数など）を最終報告に書く
   - 休止中にアプリのプロセスが止められて再生が進まない場合は、その旨と、`dumpsys` で分かったことを最終報告に書く（JVM のテストで代わりに確かめられていればよい）
3. 本物の位置で数十秒動かしてフィールドログが作られることを確かめ、取り出した CSV を `build/` の下に置いて中身を見る（室内で測位できなくても、行が出ていればよい）

## 受け入れ条件

- エンドツーエンドのテストがすべて通る
- 実機で再生ができる（またはできない理由が分かっている）
- フィールドログに位置情報が含まれない

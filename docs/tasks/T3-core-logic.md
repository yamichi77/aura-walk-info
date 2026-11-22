# T3: コアロジック（XR の UI なし）

まず [README.md](README.md) の共通ルールを読むこと。T1 で骨組みと依存関係（play-services-location、コルーチン、JUnit、coroutines-test）は入っている。

## 目的

位置 → 移動状態・距離・時間・住所 → `StateFlow<WalkSessionState>` までを作り、単体テストで確かめる。
HUD（T4）はこの `StateFlow` を表示するだけにする。仕様は PLAN.md §2.4・§3 と UI.md §4・§5。

## 並行作業との分担

T2（実機調査）が別の作業ツリーで同時に進んでいる。このタスクで触るのは次だけ。

- `app/src/main/java/io/github/yamichi77/aurawalkinfo/` の `session/`、`location/`、`safety/`、`ui/format/`（新規）、`AppContainer.kt`
- `app/src/test/` 配下

`MainActivity.kt`、`diagnostics/` は触らない。

Gradle ファイル（`gradle/libs.versions.toml`、`app/build.gradle.kts`）は、**テスト用の依存と設定を足すときだけ**触ってよい（T2 は Gradle を触らない）。後続の T4〜T6 が Compose の表示をテストで確かめられるよう、ここで次を入れておく。

- Robolectric（compileSdk に対応する版。対応していなければ `@Config(sdk = [...])` で対応する最新の SDK を指定する）
- `androidx.compose.ui:ui-test-junit4` と `ui-test-manifest`（debug）
- `androidx.test:core`、`androidx.test.ext:junit`
- `testOptions.unitTests.isIncludeAndroidResources = true`
- Robolectric と Compose UI テストが動くことを示す最小のテストを1つ書く（例: `Text("Hello")` を表示して `onNodeWithText` で見つける）

## 設計の方針

- テストしやすいように、判定ロジックは Android のクラスに依存しない純粋な Kotlin にする
  - 位置は独自の `LocationSample(elapsedRealtimeMillis, latitude, longitude, accuracyMeters, speedMps?, bearingDeg?)` で扱う。`android.location.Location` からの変換はアダプタに閉じ込める
  - 2点間の距離は `DistanceCalculator` インターフェースで注入する。本番は `Location.distanceBetween()`（PLAN.md §3.1 の `distanceTo()` と同じ計算）、テストはハバーサイン式の実装を使う
  - 時刻は `Clock`（単調増加のミリ秒）で注入する。本番は `SystemClock.elapsedRealtime()`
- 状態の更新は、位置が届いたときと、1秒ごとのティック（測位なしのタイムアウト判定と経過時間の更新のため）の両方で行う。ティックも注入できるようにし、テストでは仮想時間で進める

## 作るもの

### location/

- `LocationRepository`（インターフェース。`Flow<LocationSample>`）
  - `FusedLocationRepository`: Fused Location Provider、`PRIORITY_HIGH_ACCURACY`、1秒間隔
  - `GpsLocationRepository`: `LocationManager` の GPS プロバイダ（Play services が無いときの代替）
  - `FakeLocationRepository`: テスト用
  - どちらを使うかは `AppContainer` で Play services の有無から決める
- `DistanceTracker`（PLAN.md §3.1 の積算ルール）
  - 精度が 20m より悪い点は捨てる
  - 前回採用した点（アンカー）から `max(5m, その点の精度)` 以上離れたときだけ採用し、アンカーとの距離を足して、その点を新しいアンカーにする
  - 移動状態が「歩行中」のときだけ加算する
  - 「停止中」の間はアンカーを動かさず、加算もしない（歩き出したら、アンカーからの距離が足される）
  - 「高速移動」と「測位なし」に入ったら**アンカーを捨てる**。抜けた後の最初の採用点は、加算せずに新しいアンカーにする（高速移動中や測位なしの間に進んだ分を足さないため）
- `AddressRepository` と `JapaneseAddressFormatter`（PLAN.md §3.4、UI.md §4）
  - 逆ジオコーディングは `Geocoder` の非同期 API（`GeocodeListener`）。使う前に `Geocoder.isPresent()` を確認する。無ければ「取得失敗」の扱い
  - 更新条件: 前回の要求から30秒経過、または前回の要求地点から50m 以上移動。どちらかを満たしたら要求する（要求中に重ねて要求しない）
  - 失敗したとき（通信できない等）は、前回の値を残して「古い」印を付ける
  - 状態: `Loading`（`住所取得中…`）、`Available(parts, stale: Boolean)`、`Failed`（一度も取れていない。`—`）
  - `JapaneseAddressFormatter` は `android.location.Address` ではなく独自の `AddressParts(prefecture, city, town, chome)` を受け取る。`Address` から `AddressParts` への変換（`adminArea`、`locality` / `subAdminArea`、`subLocality`、`thoroughfare` などの使い分けと、`getAddressLine(0)` からの補完）はアダプタに分け、どのフィールドを使ったかをコメントに書く
    - 1行: 市区町村 + 町名 + 丁目（都道府県と番地は省く）。10文字を超えたら先頭9文字 + `…`（合計10文字）
    - 2行: 1行目 = 都道府県 + 市区町村、2行目 = 町名 + 丁目
    - 全角数字は半角にする（`神南１丁目` → `神南1丁目`）

### safety/

- `MotionState`: `WALKING`（歩行中）、`STOPPED`（停止中）、`FAST`（高速移動）、`NO_FIX`（測位なし）
- `MotionClassifier`（PLAN.md §2.4。閾値は変えない。値は1か所の定数か設定クラスにまとめる）
  - 速度は「3秒平均」: 直近3秒以内のサンプルの `speedMps` の平均。`speedMps` が無いサンプルは、直前のサンプルとの距離 ÷ 時間で補う
  - 判定（優先順位は 高速移動 > 測位なし > 停止中 > 歩行中）
    - 高速移動: 3秒平均 > 4.5 m/s が5秒続いたら入る
    - 測位なし: 最後の位置から10秒以上更新が無い、または最新の精度 > 30m
    - 停止中: 3秒平均 < 0.3 m/s が5秒続いたら入る
    - 歩行中: 3秒平均 ≥ 0.5 m/s
    - 0.3〜0.5 m/s の間は直前の状態を保つ（ヒステリシス）
  - **ドキュメントに無い部分は次のとおりにする**（オーケストレーターの決定。安全側に倒している）
    - 起動直後（まだ一度も測位していない）は `NO_FIX`
    - `NO_FIX` から抜けたとき、3秒平均 ≥ 0.5 m/s なら `WALKING`、それ以外は `STOPPED`
    - `FAST` から抜けるのは、3秒平均 ≤ 4.5 m/s が5秒続いたとき
- `SafetyNotice`: 初回起動時の注意文の文言（PLAN.md §2.4: 「徒歩専用。自転車・運転中は使用不可。横断歩道や駅のホームでは周囲を優先」）と、「表示済み」を覚えておく仕組み（`SharedPreferences` でよい。DataStore は依存が無ければ使わない）。表示そのものは T6 で作る

### session/

- `WalkSessionState`
  - `distanceMeters`、`walkingTimeMillis`（歩行中だけ累積）、`elapsedMillis`（セッション開始から）、`motionState`
  - `address`（`AddressRepository` の状態）
  - `mapCenter: LatLng相当?`（最新の位置。ミニマップ用。保存もログもしない）
  - `mapBearingDeg: Float?`: 0.5 m/s 以上で動いていて GPS の方位があるときはその方位。それ以外は直前の値を保つ。まだ無ければ `null`（= north-up）
  - `lastFixAccuracyMeters: Float?`（デバッグ表示用）
- `WalkSessionRepository`: 上のすべてをまとめて `StateFlow<WalkSessionState>` を出す。セッション開始はアプリ起動時（`start()`）。歩行時間と経過時間は `Clock` から計算する

### ui/format/（UI.md §4 の表示形式。純粋関数）

- 距離: 1km 未満は整数の m、1km 以上10km 未満は小数2桁の km、10km 以上は小数1桁の km。**切り捨て**にする（999.6m を `1000 m` と出さないため）。表示で数字と単位の大きさが違うので、`数字` と `単位` を分けて返す（例: `"1.24"` と `"km"`、`"850"` と `"m"`）
- 歩行時間 / 経過時間: 1時間未満は `mm:ss`、1時間以上は `h:mm:ss`。先頭に `歩行 ` / `経過 `
- 平均ペース: `分'秒"/km`（例: `15'05"/km`）= 歩行時間 ÷ 距離。距離が100m 未満なら出さない（`null`）
- 住所の表示文字列: `住所取得中…` / `—` / 1行 / 2行

### AppContainer

- 本番の実装を組み立てる（`LocationRepository` の選択、`DistanceCalculator`、`Clock`、`AddressRepository`、`WalkSessionRepository`）
- 権限が無いときに位置の購読を始めないこと（権限の要求は T6 / T4 側で行う）

## 単体テスト

`./gradlew test` で次をすべて確かめる。位置データは**合成データ**にする（例: 架空の原点か公共の場所を起点に、テストコードで生成する。GPX ファイルを使うなら合成したものを `app/src/test/resources/` に置く）。

- `DistanceTracker`
  - 1.3 m/s で直線を 500m 歩く（精度 5m、少しノイズあり）→ 誤差 ±10% 以内
  - 5分間その場で立ち止まる（精度 5〜15m のゆらぎ）→ 距離が増えない
  - 精度 25m の点は捨てられる
  - 高速移動の区間をはさむ → その区間の距離は足されない
- `MotionClassifier`
  - 歩行 → 立ち止まる → 5秒後に停止中になる（4秒では切り替わらない）
  - 0.4 m/s では状態が変わらない（ヒステリシス）
  - 5 m/s が5秒続くと高速移動。4秒では切り替わらない。抜けるのも5秒後
  - 位置が10秒来ないと測位なし。精度 35m でも測位なし
  - 優先順位（高速移動と測位なしが同時に成り立つ場合など）
- 歩行時間と経過時間: 歩行中だけ歩行時間が増える。停止中・測位なし・高速移動では増えない
- `mapBearingDeg`: 0.5 m/s 以上で方位が更新され、止まると直前の値を保つ。初めは `null`
- `AddressRepository` の更新条件（30秒、50m、要求の重複なし、失敗時は前回値 + stale）。Geocoder はインターフェースで差し替える
- `JapaneseAddressFormatter`: 1行・2行・10文字超の切り詰め・全角数字・欠けたフィールド
- 表示形式: `850 m`、`999 m`（999.6m）、`1.24 km`、`12.3 km`、`18:42`、`1:02:03`、`15'05"/km`、100m 未満でペースなし

## 確認手順

1. `JAVA_HOME` を JDK 21 にして `./gradlew test` が通る
2. `./gradlew assembleDebug` も通る

## 受け入れ条件

- `./gradlew test` が通る（上のテストがすべてある）
- テストの位置データは合成データで、実在の歩行ログを含まない
- 最終報告に、指示書に無い判断をしたところを列挙する

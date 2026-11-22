# T2: 実機の能力調査（簡易版）

まず [README.md](README.md) の共通ルールを読むこと。T1 で骨組みはできている。

## 目的

明日の散歩テストに必要な、実機（XREAL AURA）の能力を確かめて `docs/device-capabilities.md` に記録する。
**座標・住所などの位置情報は、ログにも文書にも一切書かない。**

## 並行作業との分担

T3（コアロジック）が別の worktree で同時に進んでいる。衝突を避けるため、このタスクでは次のファイルだけを触る。

- `app/src/main/java/io/github/yamichi77/aurawalkinfo/diagnostics/` 配下（新規）
- `MainActivity.kt`（プローブを起動する分岐と、入力イベントのログ出力を足すだけ）
- `app/src/debug/` 配下（必要なら）
- `docs/device-capabilities.md`（新規）

`AppContainer.kt`、`session/`、`location/`、`safety/`、Gradle ファイルは触らない。依存が足りなければ追加せず、最終報告に書く。

## 作るもの: デバッグ用プローブ

デバッグビルドでだけ動けばよい（`BuildConfig.DEBUG` で分岐）。

### 起動方法

`adb shell am start -n io.github.yamichi77.aurawalkinfo/.MainActivity --es mode <モード>`

| モード | 内容 |
|---|---|
| （指定なし） | T1 の Hello パネル（今までどおり） |
| `probe` | 下の調査項目を実行し、結果をパネルに一覧表示する。同じ内容を logcat（タグ `AuraProbe`）にも出す |
| `map` | SpatialPanel の中に `GoogleMap`（maps-compose）を1枚出す。通常モード |
| `maplite` | 同じく `GoogleMapOptions().liteMode(true)` で出す |

- 地図の表示確認用なので、`map` / `maplite` のパネルは 400dp 角程度でよい。カメラは固定の位置（公共の場所、例: 東京駅付近）、ズーム 17。ダークスタイルはまだ不要
- API キーはユーザーが今夜 `local.properties` に入れる。キーが無いと地図は灰色になるが、それでもパネルとロゴが描画されるかは見られる

### 調査項目（`probe` モード）

1. Google Play services: `GoogleApiAvailability.isGooglePlayServicesAvailable()` の結果と、`com.google.android.gms` のバージョン
2. 位置プロバイダ: `LocationManager.getAllProviders()` と、それぞれ有効か。GNSS のハードウェア情報（`gnssHardwareModelName` など、取れれば）
3. `Geocoder.isPresent()`
4. 位置の取得: 権限があれば、Fused（Play services があれば）と `LocationManager` の GPS プロバイダの両方で更新を受け、**初回測位までの秒数・精度（m）・プロバイダ名・速度と方位を持っているか**だけを記録する。緯度経度は表示もログもしない。室内で測位できなければ「室内で測位できず」と記録する
5. ARCore for Jetpack XR: `Session` を作成できるか、デバイストラッキング（PLAN.md の `DeviceTrackingMode.SPATIAL` に相当するもの。実際のライブラリの API 名に合わせる）を有効にする設定が成功するか、`ArDevice` の姿勢が更新されるか。失敗したら例外のクラス名とメッセージを記録する
   - 実行時権限 `android.permission.HEAD_TRACKING` と位置の権限は、アプリから要求する処理を入れる。検証中は `adb shell pm grant` で付与してもよい（付与したことを最終報告に書く）
6. 空間 UI: `LocalSpatialCapabilities` の各値（空間 UI が使えるか、など）

### 入力イベントのログ

- デバッグビルドで、`MainActivity` の `dispatchKeyEvent`、`dispatchGenericMotionEvent`、`dispatchTouchEvent` をオーバーライドし、届いたイベントの種類（キーコード、action、source、デバイス名）を logcat のタグ `AuraInput` に出す。イベントは消費せず、そのまま super に渡す
- パックのタッチパッドを実際に触るのはユーザー。あなたは仕組みを用意し、`adb shell getevent -lp` で入力デバイスの一覧（タッチパッドらしきデバイスの名前と対応イベント）を取って記録する

### 自動で確かめるための仕組み（ユーザーにグラスで見てもらう回数を減らすため）

- **スクリーンショット:** T1 で `adb exec-out screencap -p` を試したところ、**画像は黒一色でパネルが写らなかった。** 次の方法を順に試し、何で撮れて何で撮れないかを記録する。撮れた画像は `build/device-shots/` に置いて自分で開いて確かめる
  1. システムのスクリーンショット（`adb shell input keyevent KEYCODE_SYSRQ` の後に `/sdcard/Pictures/Screenshots/` などを確認）
  2. `adb shell screenrecord --time-limit 3 /sdcard/x.mp4`（取り出して1コマ目を見る）
  3. **アプリ内でのパネルの中身のキャプチャ（本命）:** Compose の `rememberGraphicsLayer()` + `drawWithContent { graphicsLayer.record { ... } }` + `toImageBitmap()` で、パネルの中身を PNG にしてアプリの files ディレクトリに保存するデバッグ機能を作る。`adb shell am broadcast` か intent extra（例: `--ez capture true`）で起動し、`adb exec-out run-as io.github.yamichi77.aurawalkinfo cat files/<名前>.png > build/device-shots/<名前>.png` で取り出す。3D 上の位置は写らないが、パネルに何が描かれているか（文字・色・透明な背景）は確かめられる。T4 以降もこの仕組みを使うので、再利用しやすい形（どのパネルにも付けられる Modifier など）にする
- **地図の描画の自己診断（`map` / `maplite`）:** maps-compose の `onMapLoaded` が呼ばれたか、呼ばれるまでの秒数、`GoogleMap.snapshot()` で取った画像の「黒以外の画素の割合」と「平均の明るさ」を logcat（タグ `AuraProbe`）に出す。API キーが無いときとあるときで数値がどう変わるかも、分かる範囲で書く
- **タッチパッド:** `adb shell getevent -lp` の入力デバイス一覧から、タッチパッドがどのクラスのデバイスか（タッチスクリーン / タッチパッド / マウス / キー）を推定する。さらに、`adb shell input` で KeyEvent や MotionEvent を注入して、アプリのログ出力が動くことを確かめる。実際のタップで何が届くかは、最後にユーザーに10秒だけ触ってもらう（手順を最終報告に書く）

## 確認手順

1. `JAVA_HOME` を JDK 21 にして `./gradlew assembleDebug` が通る
2. AURA にインストールして `probe` モードで起動し、`adb logcat -s AuraProbe` の結果を集める
3. `map` / `maplite` モードで起動し、クラッシュしないことと、上の自己診断の数値を集める
4. スクリーンショットが撮れるかを確かめる
5. `docs/device-capabilities.md` を書く

## `docs/device-capabilities.md` の書き方

- 冒頭に調査日（2026-11-22）、端末（`adb shell getprop ro.product.model` / `ro.build.fingerprint` のうち公開して問題ない範囲。シリアル番号は書かない）、使ったライブラリのバージョン
- 調査項目ごとに「結果」「確かめ方」「アプリへの影響」を表で書く
- グラスで見ないと分からない項目（地図が描画されるか、タッチパッドのイベント）は「未確認（ユーザー確認待ち）」と書き、確認方法を添える
- T4 で使う「回転の符号」の欄を空けておく（T4 で埋める）

## 受け入れ条件

- `assembleDebug` が通り、`probe` / `map` / `maplite` がクラッシュせずに起動する
- `docs/device-capabilities.md` に上の項目が記録されている（位置情報なし）
- 最終報告に、Play services の有無、位置プロバイダ、Geocoder、ARCore の結果をまとめる

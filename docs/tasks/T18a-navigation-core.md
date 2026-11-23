# T18a: ナビの中身（目的地の検索・経路・案内の計算）（2026-11-23 ユーザー承認）

まず [README.md](README.md) の共通ルールを読むこと。T1〜T13 は main に入っている。追加機能の全体は [PLAN.md](../PLAN.md) §9。

**このタスクは別のワークツリーで、T14・T15・T17a と並行して進む。** 自分の範囲の外のファイル（HUD の表示、北の向き、BLE）には手を入れない。共有のファイル（`AndroidManifest.xml`、`AppContainer.kt`、`MainActivity.kt`、`gradle/libs.versions.toml`、`app/build.gradle.kts`）は、必要な行の追加だけにとどめ、並べ替えや整形をしない。

## 目的

施設名から目的地を探し、徒歩の経路を取り、歩いている間の案内（次の曲がり角までの距離、曲がる向き、残りの距離、ルートから外れたときの取り直し、到着）を計算する。表示（HUD）と音声入力は T18b で作る。**このタスクではネットワークに実際につながない**（API の有効化はユーザーが受け入れの前に行う）。テストはすべて偽物の通信と合成データで行う。

## API と料金（PLAN.md §9.1）

- 施設の検索: **Places API (New) の Text Search**（`POST https://places.googleapis.com/v1/places:searchText`）
  - フィールドマスクは `places.id,places.displayName,places.location,places.formattedAddress` に**固定**する（この組み合わせは Pro の料金。これ以上のフィールド、たとえば評価や営業時間を足すと高い料金になるので足さない）
  - 本文: `textQuery`、`languageCode: "ja"`、`regionCode: "JP"`、今の位置を中心にした半径 5km の `locationBias`、候補は最大 5 件
- 経路: **Routes API の computeRoutes**（`POST https://routes.googleapis.com/directions/v2:computeRoutes`）
  - `travelMode: "WALK"`、`languageCode: "ja-JP"`、`units: "METRIC"`
  - フィールドマスクは `routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline,routes.legs.steps.distanceMeters,routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.navigationInstruction,routes.legs.steps.startLocation,routes.legs.steps.endLocation` に固定する
  - **高い料金（Pro）になる機能を使わない:** 経由地、`optimizeWaypointOrder`、`routingPreference`、location modifier（heading など）は付けない
- API キー: 今のマニフェストの `com.google.android.geo.API_KEY`（`MAPS_API_KEY`）を実行時に読んで使う。新しいキーは増やさない
  - ヘッダー: `X-Goog-Api-Key`、`X-Goog-FieldMask`、`X-Android-Package`（パッケージ名）、`X-Android-Cert`（署名証明書の SHA-1 を、区切りなしの大文字 16 進で。実行時に `PackageManager` から求める）
  - キーやヘッダーの値をログに出さない
- 使いすぎの防止（アプリ側）: 端末の中で1日の回数を数え、Places は 1 日 150 回、Routes は 1 日 300 回を超えたら呼ばずにエラーにする（Google Cloud の上限と同じ値。定数1か所にまとめる）。日付は端末のタイムゾーンで区切る

## 作るもの（新しいパッケージ、例: `nav/`）

1. **通信の部分:** Places と Routes のリクエストを作り、レスポンスを解析する
   - HTTP は `HttpURLConnection`（標準）でよい。新しい実行時の依存はなるべく増やさない。増やす場合は `gradle/libs.versions.toml` でバージョンを固定する
   - リクエストの組み立てとレスポンスの解析は、通信と切り離してテストできるようにする（通信は差し替えられるインターフェースにする）
   - エラーの種類: 通信できない、API キーや権限のエラー（403 など）、結果なし、1日の上限、その他
2. **エンコードされたポリラインの復号:** 自前で実装する（Google の公開アルゴリズム）
3. **案内の計算（純粋な Kotlin）:** 経路（ポリラインの点とステップ）と位置の更新から、次を計算する
   - 経路上の今の位置（いちばん近い線分への射影）と進み具合
   - 次の曲がり角: 次のステップの始点。そこまでの経路に沿った距離、曲がる向き（`navigationInstruction.maneuver` から左・右・まっすぐ・U ターンなどに分類）、案内の文（`navigationInstruction.instructions`）
   - 残りの距離と、残りの時間の目安（経路の距離と時間の比で計算してよい）
   - 次の曲がり角と目的地への方位（北 0°、時計回り）。空の方位帯（T19b）と視界の端の矢印（T19a）で使う
   - **ルートから外れた判定:** 精度が 30m 以下の測位で、経路からの距離が `max(25m, 精度 × 2)` を超える状態が 8 秒続いたら外れたとみなし、経路を取り直す。取り直しは 30 秒以上の間隔をあけ、1 回のナビで最大 20 回まで（料金のため）
   - **到着:** 精度が 25m 以下の測位で、目的地から 20m 以内に入ったら到着
   - 測位なし（精度が悪い・更新がない）の間は、案内の値を最後のまま保ち、外れた判定と到着の判定をしない
4. **ナビの状態の管理:** `StateFlow<NavState>` を流す。状態の例: 何もしていない / 検索中 / 候補あり（最大 3 件、名前・住所・今の位置からの距離）/ 経路を取得中 / 案内中 / 取り直し中 / 到着 / エラー
   - 操作: 施設名で検索する、候補を選ぶ（経路を取って案内を始める）、緯度経度で直接目的地にする（デバッグ用と将来の地図での指定用）、ナビをやめる
   - 到着したら到着の状態を 10 秒出してから、何もしていない状態に戻る
   - 目的地の履歴は保存しない（今回は持たない）
   - `AppContainer` で組み立てる。HUD にはまだつながない（T18b でつなぐ）
5. **デバッグ用:** ブロードキャスト（例: `io.github.yamichi77.aurawalkinfo.NAV`）で、`--es query "<施設名>"` の検索、`--ef lat --ef lng` の直接指定、`--es action cancel`、そして**通信を使わない合成の経路**（`--es scenario synthetic`。既存の合成ルート `SyntheticLocations` の原点（緯度 0、経度 0）の周りに作る）を流せるようにする。デバッグビルドだけ
   - 合成の経路は、既存の `REPLAY` の合成ルートと組み合わせて、曲がり角の案内と到着が実機でも再現できるようにする

## 確認手順

1. `./gradlew test assembleDebug` が通る（JDK 21）
2. 単体テスト（ネットワークに出ない）
   - ポリラインの復号: Google のドキュメントの例 `_p~iF~ps|U_ulLnnqC_mqNvxq`@` が (38.5, -120.2)、(40.7, -120.95)、(43.252, -126.453) になる
   - リクエスト: URL、ヘッダー（フィールドマスクが上の文字列と完全に一致する、`X-Android-Package` と `X-Android-Cert` がある）、本文（`travelMode` が `WALK`、Pro になる項目が入っていない、`locationBias` がある）
   - レスポンスの解析: 合成の JSON（候補あり、候補なし、ステップつきの経路、エラーの JSON）
   - 案内の計算: 進み具合、次の曲がり角までの距離と向き、曲がり角を過ぎたら次へ進む、方位、残りの距離
   - ルートから外れた判定: 8 秒の境目、精度が悪いときは判定しない、取り直しの間隔（30 秒）と回数（20 回）
   - 到着: 20m の境目、精度が悪いときは判定しない、10 秒後に戻る
   - 1日の上限: 150 回と 300 回で止まる、日付が変わると戻る
3. 既存のテストがすべて通る
4. **実機（adb）は使わない。実際の API も呼ばない**

## 受け入れ条件

- 上の確認がすべて通る
- UI.md と PLAN.md を変更していない
- API キーがコードやログに書かれていない

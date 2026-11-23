# aura-walk-info プロジェクト計画

作成日: 2026-11-22（計画: Opus / 実装: 別セッション）
更新: 2026-11-22 歩数をやめ、GPS で測る「歩いた距離」に変更（パックのスペック表に歩数センサーの記載がないため）

XREAL AURA（Android XR の有線XRグラス）で、徒歩中に以下の情報を**視界の正面を避けた位置**に表示するアプリ。

- 歩いた距離
- 歩いている時間
- マップ
- 現在の住所

---

## 1. 前提と調査結果（2026-11 時点）

### 1.1 デバイス: XREAL AURA

| 項目 | 内容 | 本アプリへの影響 |
|---|---|---|
| 形態 | グラス本体 + 有線接続のコンピュートパック（Android XR が動くのはパック側） | アプリはパック上で動作。スマホは不要 |
| ディスプレイ | 光学シースルー、対角 FOV 70°、1920×1200/眼、最大120Hz | 16:10 と仮定すると**およそ水平61°×垂直41°**。UIはこの枠内に収める |
| 表示方式 | 加法合成。**黒は透明に見える** | 背景塗りつぶしは禁止。黒地に明るい線と文字で描く |
| 調光 | エレクトロクロミック調光（5段階、自動/手動） | ユーザー設定を尊重し、アプリからは触らない |
| トラッキング | グラスの前向きカメラ×2で 6DoF、ハンドトラッキング | `ArDevice` で頭部姿勢を取得できる |
| 位置 | パックに GNSS（GPS / GLONASS / Galileo / **QZSS** / BeiDou）、Wi-Fi 6/6E | 位置情報はパック単体で取得できる見込み |
| 入力 | ハンド、音声、**パックのタッチパッド** | 歩行中の操作はタッチパッドが現実的 |
| 通信 | Wi-Fi、Bluetooth 5.3。**セルラーなし** | 屋外での地図タイル・住所取得はテザリング前提。オフライン時の表示も設計する |
| 歩数センサー | **スペック表に記載なし**（加速度・ジャイロ・地磁気も記載なし） | 歩数は扱わない。代わりに GPS で**歩いた距離**を出す（2026-11-22 決定） |

### 1.2 開発スタック（Jetpack XR SDK）

- Android Studio の最新 Canary と **XR Glasses AVD**（Preview API のシステムイメージ）で、実機なしでも検証できる
  - ホスト要件: Windows 11、RAM 16GB、VRAM 8GB、GeForce 10 / Radeon RX 5000 以降、BIOS で VMX を有効化
- 主要ライブラリ（2026-11-22 時点。実装開始時に set-up-sdk ページで最新版を確認すること）

  ```kotlin
  implementation("androidx.xr.runtime:runtime:1.1.0-alpha03")
  implementation("androidx.xr.scenecore:scenecore:1.1.0-alpha02")
  implementation("androidx.xr.compose:compose:1.0.0-rc01")
  implementation("androidx.xr.compose.material3:material3:1.0.0-alpha18")
  implementation("androidx.xr.arcore:arcore:1.1.0-alpha02")
  ```
  - 注意: `compose 1.0.0-beta02` 以降が依存する `scenecore 1.0.0-rc01` にはメモリリークがある。依存解決後のバージョンを確認する
- **頭に追従する UI**（実験的 API: `@OptIn(ExperimentalFollowingSubspaceApi::class)`）

  ```kotlin
  Subspace(
      follow = FollowTarget.view(
          mode = FollowMode.tight(TrackedDimensions.All) // or FollowMode.soft(...)
      ),
  ) {
      SpatialPanel(SubspaceModifier.offset(x = ..., y = ..., z = ...).rotate(pitch = ..., yaw = ...)) { ... }
  }
  ```
  - `FollowMode` には `snap` / `soft` / `tight` がある。`TrackedDimensions` で追従する軸（X/Y/Z/pitch/yaw/roll）を選べる
  - `SubspaceModifier.offset` の単位は dp、`rotate` の単位は度。**回転の符号の向きとサブスペース原点の位置はドキュメントに書かれていない**ため、Phase 2 でデバッグ用の角度グリッドを使って実測する
  - `SubspaceModifier.gravityAligned()` を使うと pitch と roll を打ち消せる
- 頭部姿勢: `Config.Builder(session.config).setDeviceTracking(DeviceTrackingMode.SPATIAL)` で有効化し、`ArDevice.getInstance(session).state` から取得する。実行時権限 `android.permission.HEAD_TRACKING` が必要
- Geospatial API（VPS）は Wired XR Glasses に対応と記載がある。地球基準の方位（北）が取れるので、将来の拡張候補にする
- Spatial UI は Full Space でのみ使える。そのため起動モードは `XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED` にする

### 1.3 公式ガイドラインのうち本アプリに効くもの

- 「Keep the central field of view clear of dense, opaque elements」: 中央視野に不透明で密度の高い要素を置かない（つまずきや周囲への注意低下を防ぐため）
- XR グラスでは**常にダークテーマ**にする
- 文字は 14dp 以上、通常以上のウェイトにする
- 情報は必要なときだけ出し、用が済んだら引っ込める
- 基準フレームを一貫させる（UIを不意にスナップさせたり回転させたりしない）

---

## 2. 安全設計の方針（最重要・変更時はユーザー確認）

> 文字サイズ・色・表示形式まで含めた UI の詳細仕様は [UI.md](UI.md) にある（2026-11-22 承認済み）。実装はそちらに従う。

### 2.1 配置ルール

```
            ← 水平 約61°（表示可能範囲） →
  ┌──────────────────────────────────────────────┐  +20°
  │                                              │
  │            ┌──────────────────┐              │
  │            │                  │              │
  │            │  中央クリアゾーン  │              │   0°（目線）
  │ ┌───────┐  │   水平 ±15°      │  ┌───────┐   │
  │ │ 距離  │  │   全高さで       │  │ ミニ  │   │  -8°
  │ │ 時間  │  │   UI禁止         │  │ マップ │   │
  │ │ 住所  │  │                  │  │       │   │
  │ └───────┘  └──────────────────┘  └───────┘   │
  └──────────────────────────────────────────────┘  -20°
      左クラスタ                       右クラスタ
     (yaw 左22°)                     (yaw 右22°)
```

- **中央クリアゾーン: 水平 ±15° の範囲には、上下とも何も置かない。** 前方と足元（歩行中の視線は目線のやや下に落ちる）を両方空けておくため
- 左クラスタ（中心: 左22°、下8°）には距離、歩行時間、住所を置く
- 右クラスタ（中心: 右22°、下8°）にはミニマップを置く
- 上の値はすべて初期値。`HudLayoutConfig` にまとめて、プリセット（左右寄せ / 下寄せ / 上寄せ）を切り替えられるようにする。最終値は Phase 4 の実地テストで決める
- パネル距離の初期値は 1.5m。グラスの焦点距離と見やすさに合わせて調整する

### 2.2 追従モード

| モード | 挙動 | 位置づけ |
|---|---|---|
| A. ヘッドロック | `FollowMode.tight(All)`。常に視界の同じ位置に出る | **MVP の既定**。予測しやすく、HUD として素直 |
| B. ソフト追従 | `FollowMode.soft(All)`。少し遅れて追いかける | 酔いやちらつきの比較用 |
| C. 水平固定 | yaw と位置だけ追従し、pitch は重力に揃える（`gravityAligned`） | 「うつむくと見える」方式の比較用 |

- 歩行中は前に進むので、**位置（X/Y/Z）の追従は必須**。追従しないとパネルが後ろに置いていかれる
- 屋外で 6DoF トラッキングが途切れる場合の挙動を Phase 0 と Phase 4 で確認する。破綻するなら回転のみの追従に切り替える

### 2.3 表示スタイル（加法表示向け）

- 背景は透明（黒）。パネルの塗りは一切なし。マップの円枠だけ 30% 程度の細線にする
- 文字色は白または淡いシアン、アクセントは緑。大きな明るい面を作らない
- 距離は大きな数字（32dp 程度）、ラベルは 14dp 以上
- 歩行中は不透明度 70%、停止中は 100%（初期値）

### 2.4 状況に応じた表示制御

| 状態 | 判定（初期値） | 表示 |
|---|---|---|
| 歩行中 | GPS 速度（3秒平均）≥ 0.5m/s | コンパクト表示（マップは小さく、住所は1行） |
| 停止中 | GPS 速度（3秒平均）< 0.3m/s が5秒続く | 詳細表示（マップを拡大、住所は2行、経過時間と平均ペースも表示） |
| 測位なし | 位置が10秒以上更新されない、または精度 > 30m | 「GPS 測位中…」と小さく出す。距離と歩行時間は加算しない |
| 高速移動 | GPS速度 > 4.5m/s（約16km/h）が5秒続く | **HUD を非表示**にし、「高速移動中のため非表示」とだけ小さく出す（自転車や乗り物を想定） |
| 頭の回転中 | 頭の角速度 > 30°/秒（15°/秒未満が 0.3 秒続いたら解除） | （無効。2026-11-23 ユーザー判断。UI.md §5） |

- 初回起動時に注意文を出す。内容は「徒歩専用。自転車・運転中は使用不可。横断歩道や駅のホームでは周囲を優先」
  - 参考: 自転車運転中の画像注視は道路交通法で禁止されている
- パックのタッチパッドのタップで HUD の表示と非表示を切り替えられるようにする（実機で入力イベントが取れるか Phase 0 で確認）

---

## 3. 機能仕様

### 3.1 歩いた距離

- セッション開始（アプリ起動）からの距離を、GPS の位置を積算して出す。歩数は扱わない
- 積算のルール（初期値）
  - 精度（`Location.accuracy`）が 20m より悪い測位は捨てる
  - 前回採用した点から `max(5m, 精度)` 以上離れたときだけ採用し、その間の距離を足す。停止中の GPS のゆらぎで距離が増えるのを防ぐため
  - 移動状態が「歩行中」のときだけ加算する。「高速移動」と「測位なし」の間は加算しない
  - 2点間の距離は `Location.distanceTo()` で求める
- 表示形式: 1km 未満は `850 m`、1km 以上は `1.23 km`
- 平均ペース（分/km）= 歩行時間 ÷ 距離。停止中の詳細表示でだけ出す（100m 未満のうちは出さない）
- 制約: 地下・駅構内・ビルの谷間では GPS が届かず、距離が伸びない。そのときは「GPS 測位中…」と出し、推定で埋めない
- `DistanceTracker` として、記録した位置データ（GPX）を使った単体テストを書く

### 3.2 歩いている時間

- 主表示は**歩行時間**: 「歩行中」と判定された時間だけを累積する（信号待ちでは止まる）
- 副表示は**経過時間**: セッション開始からの時間。停止中の詳細表示でだけ出す
- 表示形式は `mm:ss`、1時間を超えたら `h:mm:ss`

### 3.3 マップ

- **Google Maps を使う（2026-11-22 決定）。** maps-compose（`GoogleMap`）に JSON スタイル（`MapStyleOptions`）を当ててダークスタイルにする
  - Map ID を使わなければ Maps SDK for Android は無料・無制限（Map ID を使うと月1万ロードを超えた分が課金対象になるため、使わない）
  - スタイルの目安: 地面は `#000000`、道路は `#3A3A3A`〜`#808080`、水域は暗い青、POI は非表示、ラベルは最小限
  - API キーは `local.properties` に置き、secrets-gradle-plugin で注入する。**コミットしない**
- 現在地は明るいシアンの矢印で示し、ズームは徒歩用に 17〜18 程度にする
- 向き: 0.5m/s 以上で動いているときは GPS の進行方位に合わせて heading-up にする。それ以外は直前の方位を保持し、方位がまだ無ければ north-up にする
  - パックの方位センサーはパックの向きであって頭の向きではないので、使わない
- **SpatialPanel 内で Maps SDK が正しく描画されるかはドキュメントに書かれていない**（Phase 0 で確認）。描画できない場合は、まず Google Maps の範囲で回避策（ライトモード `GoogleMapOptions.liteMode(true)` など）を試す。それでも駄目なら、ほかの地図に切り替える前にユーザーに相談する

### 3.4 現在の住所

- `android.location.Geocoder` の非同期 API（API 33以降の `GeocodeListener` 版）で逆ジオコーディングする
  - 使う前に `Geocoder.isPresent()` を確認する。結果は best-effort として扱う
- 更新条件は「30秒経過」または「50m 以上移動」のどちらか。ただし通信できないときは前回値を薄く表示する
- 表示形式は日本向けに短く「東京都渋谷区神南1丁目」程度にとどめ、番地は省く。`JapaneseAddressFormatter` として単体テストを書く
- 取得前は「住所取得中…」、取得できなければ「—」と表示する

### 3.5 位置情報

- Fused Location Provider（`PRIORITY_HIGH_ACCURACY`、1秒間隔）を使う。パックに Google Play services があるかは Phase 0 で確認し、無ければ `LocationManager` の GPS プロバイダにする
- 権限は `ACCESS_FINE_LOCATION`
- MVP では位置の履歴を保存しない（端末外にも送らない）

---

## 4. アーキテクチャ

- 言語と UI: Kotlin、Jetpack Compose for XR、Material3（加法表示向けの独自ダークテーマ）
- 構成は単一モジュールの MVVM。DI は手動（`AppContainer`）
- 非 XR 端末（普通の Android スマホ）では 2D 画面にフォールバックする
  - `LocalSpatialCapabilities` で判定する
  - コアロジック（距離・位置・住所）はスマホでも検証できるようにする
- `minSdk 34` / `compileSdk` は最新
- ビルド設定は Gradle Version Catalog（`libs.versions.toml`）で管理する

```
app/src/main/java/<package>/walkinfo/
├── MainActivity.kt
├── AppContainer.kt
├── session/        WalkSessionRepository, WalkSessionState（距離・歩行時間・経過時間・移動状態）
├── location/       LocationRepository, DistanceTracker, AddressRepository, JapaneseAddressFormatter
├── safety/         MotionClassifier（歩行/停止/高速）, SafetyNotice
├── settings/       SettingsRepository（DataStore）, HudLayoutConfig
├── diagnostics/    CapabilityProbeScreen（Phase 0 用）
└── ui/
    ├── theme/      AdditiveDisplayTheme
    ├── hud/        HudScene, HudPlacement（角度→offset 変換）, StatsPanel, AddressPanel, MiniMapPanel, DebugAngleGrid
    └── fallback/   PhoneScreen（2D）
```

データの流れ:
`LocationRepository` → `MotionClassifier` / `DistanceTracker` / `AddressRepository` → `WalkSessionRepository`（`StateFlow<WalkSessionState>`）→ `HudViewModel` → `HudScene`

---

## 5. フェーズ計画

各フェーズは「受け入れ条件」をすべて満たしたら完了とし、次に進む。

### Phase 0: 環境構築と実機能力の調査

- [x] `git init`、`.gitignore`、betterleaks（コミット前フックと GitHub Actions）を整える（2026-11-22 済）
- [ ] Android Studio Canary と XR Glasses AVD を用意する
- [ ] プロジェクトの骨組みを作る（XR の依存関係とマニフェスト設定）
  - `<uses-feature android:name="android.software.xr.api.spatial" android:required="false" />`（スマホでも動かすため `false`）
  - `PROPERTY_XR_ACTIVITY_START_MODE = XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED`
  - 権限: `ACCESS_FINE_LOCATION`, `HEAD_TRACKING`, `INTERNET`
- [ ] Google Cloud で Maps SDK for Android を有効にし、API キーを作る（ユーザー作業）。キーはパッケージ名と SHA-1 で制限し、`local.properties` に置く
- [ ] **CapabilityProbe 画面**を作り、以下を一覧表示し、ファイルにも書き出す
  - 位置プロバイダ、Google Play services の有無、`Geocoder.isPresent()`
  - 屋外での測位精度（`Location.accuracy`）と、初回測位までの時間
  - `DeviceTrackingMode.SPATIAL` を設定できるか、Geospatial に対応しているか
  - パックのタッチパッド操作で届く入力イベント（`KeyEvent` / `MotionEvent` の種類）
  - SpatialPanel の中に `GoogleMap` を置いたときに描画されるか
- [ ] 結果を `docs/device-capabilities.md` にまとめ、地図の描画方法（通常表示で出るか、ライトモードが必要か）を決める
- 受け入れ条件: エミュレータで起動できる。実機があれば実機の調査結果が文書化され、§3.3 の方式が決まっている

### Phase 1: コアロジック（XR の UI なし）

- [ ] `LocationRepository`（テスト用の Fake つき）と `AddressRepository`（更新条件の制御つき）
- [ ] `DistanceTracker`（§3.1 の積算ルール）
- [ ] `JapaneseAddressFormatter`
- [ ] `MotionClassifier`（歩行 / 停止 / 高速）
- [ ] `WalkSessionRepository`（歩行時間の累積と経過時間）
- [ ] 2D のフォールバック画面に値をそのまま表示する
- [ ] 単体テスト: 距離の積算（GPX データ。停止中のゆらぎを含む）、時間の累積、移動状態の判定、住所の整形
  - GPX は合成データか公共の場所のものを使う。public リポジトリなので、自宅周辺の実ログはコミットしない
- 受け入れ条件: `./gradlew test` が通る。スマホまたはエミュレータで距離・時間・住所が更新される（エミュレータでは GPX ルートを再生して確認）

### Phase 2: XR HUD（MVP）

- [ ] Full Space で `Subspace(follow = FollowTarget.view(FollowMode.tight()))`
- [ ] `HudPlacement` を作る: 角度（yaw/pitch）と距離から offset と rotate を計算する
- [ ] `DebugAngleGrid` を作る: 10°/15°/20°/30° の同心リングと中央クリアゾーンの枠を出し、配置を目で確認する（デバッグビルドのみ）
- [ ] 左クラスタ（距離・歩行時間・住所）と右クラスタ（ミニマップ。この段階では仮の円と矢印）
- [ ] `AdditiveDisplayTheme`（透明背景、明るい文字、塗りなし）
- 受け入れ条件: XR Glasses AVD で、左右のクラスタが中央クリアゾーンに一切かからない。頭を動かしても同じ位置に出る

### Phase 3: マップ

- [ ] maps-compose でダークスタイルを実装し、heading-up 回転を付ける（描画方法は Phase 0 の結果に従う）
- [ ] 現在地マーカー、ズーム固定、ジェスチャ無効
- [ ] オフライン時の表示（最後に読み込んだタイルのまま「オフライン」と小さく出す）
- 受け入れ条件: 地図の明るい面積が小さく、背景が透けて見える。GPX を再生すると地図が進行方向に回転する

### Phase 4: 安全機能と実地調整

- [ ] 歩行中 / 停止中の表示切替、不透明度の制御
- [ ] 高速移動時の非表示
- [ ] 初回の注意文表示
- [ ] タッチパッドでの表示と非表示の切替
- [ ] 追従モード A / B / C と配置プリセットを、設定画面または開発用メニューから切り替えられるようにする
- [ ] **実地テストのチェックリスト**（下記）を実施し、初期値を確定する
  - 距離の精度: 地図上で長さが分かるコース（例: 500m）を歩き、誤差 ±10% 以内か。5分間立ち止まっても距離が増えないか
  - 明るい屋外 / 夕方 / 夜間での視認性と、背景の透け具合
  - 交差点や人混みで、中央の視界が遮られていないという主観評価
  - 追従モードごとの酔いやちらつき
  - トラッキングが途切れたときの挙動、30分歩いたときの発熱とバッテリー消費
- 受け入れ条件: チェックリストを全項目実施し、結果と確定したパラメータを `docs/field-test.md` に記録する

### Phase 5: 拡張（任意）

- Geospatial API による正確な頭の方位（heading-up 地図の改善）
- セッション履歴（ローカル保存）、Health Connect への書き込み
- フォアグラウンドサービス化（他のアプリを開いていても計測を続ける）
- 音声コマンド（「距離リセット」など）

---

## 6. リスクと対策

| リスク | 影響 | 対策 |
|---|---|---|
| GPS のゆらぎ・マルチパス（ビル街） | 距離が実際より伸びる、または縮む | §3.1 の積算ルールで抑え、Phase 4 で閾値を調整する |
| 地下や屋内で GPS が届かない | 距離と歩行時間が止まる | 「GPS 測位中…」と表示し、推定で埋めない |
| SpatialPanel 内で Maps SDK が描画されない | 地図を表示できない | ライトモードなど Google Maps の範囲で回避し、駄目ならユーザーに相談する |
| AURA に Google Play services が無い | Google Maps と Fused Location が使えない | Phase 0 で確認する。無ければユーザーに相談する |
| 屋外で 6DoF トラッキングが不安定 | HUD が揺れる、置いていかれる | 回転のみの追従にフォールバックする。Phase 4 で検証 |
| 実験的 API（Following Subspace）の仕様変更 | ビルドが壊れる | 追従処理は `HudScene` の中に閉じ込め、ライブラリのバージョンを固定する |
| セルラーがなく屋外で通信できない | 地図タイルと住所が出ない | テザリングを前提にしつつ、オフライン表示を用意する |
| 明るい屋外で表示が見えにくい | 情報を読めない | 文字を太めにし、調光はユーザー設定に任せる |

---

## 7. 実装担当セッションへの申し送り

- このファイルを正とする。**§2（安全設計）の数値やルールを変える場合は、ユーザーに確認してから変える**
- フェーズの順番を守る。各フェーズの終わりに受け入れ条件を確認し、結果を報告する
- 実験的 API には `@OptIn` を明記し、バージョンは `libs.versions.toml` で固定する
- ドキュメントに書かれていない挙動（回転の符号、サブスペースの原点、入力イベントの種類）は推測で決めない。エミュレータや実機で確かめ、`docs/device-capabilities.md` に記録する
- API キーなどの秘密情報はコミットしない

---

## 8. 決定事項と未決事項

決定済み（2026-11-22）
- 歩数は扱わず、GPS で測る歩いた距離を出す
- 地図は Google Maps（maps-compose、Map ID なし）
- GitHub で public に管理し、betterleaks で秘密情報の混入を防ぐ（コミット前フックと GitHub Actions）
- AURA の実機はユーザーの手元にある。2026-11-23 の散歩で実機テストする（範囲と進め方は [HANDOFF.md](HANDOFF.md)）
- UI 設計は承認済み。詳細は [UI.md](UI.md)、参照モックは [ui-mockup.html](ui-mockup.html)（左に距離・歩行時間・住所、右にミニマップ、中央 ±15° は空ける、状態ごとの表示切替）

未決（ユーザー確認待ち）
1. 配布範囲: 自分用のサイドロードか、Play ストアで公開するか（公開するならプライバシーポリシーや品質ガイドラインへの対応が増える）

---

## 参考

- XREAL AURA 技術仕様: https://tutorials.xreal.com/docs/aura/specifications/
- Android XR デバイス種別: https://developer.android.com/develop/xr/devices
- Jetpack XR SDK セットアップ: https://developer.android.com/develop/xr/jetpack-xr-sdk/set-up-sdk
- Subspace の追従: https://developer.android.com/develop/xr/jetpack-xr-sdk/subspace-follow-target
- 頭部姿勢（ARCore for Jetpack XR）: https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/device-pose
- Geospatial: https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/geospatial
- Spatial UI デザイン: https://developer.android.com/design/ui/xr/guides/spatial-ui
- 品質ガイドライン（イマーシブ）: https://developer.android.com/docs/quality-guidelines/android-xr/immersive
- XR Glasses AVD: https://developer.android.com/develop/xr/jetpack-xr-sdk/run/create-avds/xr-headsets-glasses
- Google Maps Platform 料金: https://developers.google.com/maps/billing-and-pricing/pricing
- Geocoder: https://developer.android.com/reference/android/location/Geocoder

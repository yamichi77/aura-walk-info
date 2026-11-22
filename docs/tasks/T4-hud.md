# T4: HUD（ヘッドロック表示・角度ガイド・左クラスタ・状態ごとの表示）

まず [README.md](README.md) の共通ルールを読むこと。T1〜T3 は完了している。T2 の結果は `docs/device-capabilities.md` にある。

## 目的

T3 の `StateFlow<WalkSessionState>` を、UI.md どおりの位置と形式で HUD に表示する。
配置を実機で確かめるためのデバッグ用の角度ガイドも作る。**UI.md の配置・中央クリアゾーン・状態ごとの表示は変えない。**

## T2 で分かった実機の制約（必読）

- **グラスを装着していないと表示がスリープする**（`dumpsys power` の `mLastSleepReason=xr_doff`、`mInteractive=false`）。このとき `LocalSpatialCapabilities` は全部 `false`、`ArDevice` は `STOPPED` になり、空間 UI の描画も配置の自己診断もできない。adb からの起床やスリープ抑止では復帰しない
- そこで、実機での確認は**装着中に1コマンドで全部回せるスクリプト**にまとめる（下の「実機確認スクリプト」）。作業の最初に `adb shell dumpsys power | grep -E "mWakefulness|mLastSleepReason|mInteractive"` で状態を見て、装着中ならスクリプトまで実行する。非装着なら、スクリプトは非装着でも進める範囲まで動作を確かめ、残りは最終報告に書く。オーケストレーターが、ユーザーの装着中にスクリプトを実行する
- adb の作法（T2 の知見）: インストールは `adb install -r --no-streaming`、起動は `adb shell am start -S ...`（`-S` で毎回起動し直さないと診断が走らない）
- アプリ内キャプチャは T2 の `capturePanelOnNextDraw("名前.png")`（`diagnostics/PanelCapture.kt`）を使う
- デバッグビルドの判定は T2 が `ApplicationInfo.FLAG_DEBUGGABLE` で行っている（AGP 9 は `BuildConfig` が既定で無効）。判定は1か所の関数にまとめて使い回す

## 並行作業との分担

T6a（合成ルートの再生とフィールドログ）が別の worktree で同時に進んでいる。T6a は `AppContainer.kt`、`session/`、`location/`、`safety/`、`diagnostics/replay/`、`diagnostics/fieldlog/` を触る。このタスクではそれらを**触らない**（読むだけ）。

- `AppContainer` はこのタスクで新しく作る `AuraWalkApplication`（`Application` のサブクラス、`val container: AppContainer`）が1つだけ持つ。マニフェストの `android:name` に登録する。アクティビティの作り直しでセッションが途切れないようにするため
- `WalkSessionRepository.start(scope)` は、`AuraWalkApplication` が持つアプリ全体のスコープで1回だけ呼ぶ（位置の権限が後から付与されても、T3 の `AppContainer` が1秒ごとに確かめて購読を始める）

## 作るもの

### ui/hud/HudPlacement

- 入力: yaw（度。UI.md の「左」「右」）、pitch（度。「上」「下」）、距離（m、初期値 1.5）
- 出力: `SubspaceModifier.offset(x, y, z)` と `rotate(...)` に渡す値
  - 位置は頭を中心とした球面上（x = d·sin(yaw)·cos(pitch)、y = d·sin(pitch)、z = −d·cos(yaw)·cos(pitch) のような形）にし、パネルが頭の方を向くように回転させる
  - **m → dp の換算は、ライブラリの換算 API（`androidx.xr.compose.unit.Meter` の `toDp()` など）があればそれを使う。** 無ければ換算方法を最終報告に書く
  - 符号（左が −x か、yaw の正が左回りか右回りか）は**この関数の中の定数1か所**で切り替えられるようにする。初期値は OpenXR の慣例（+x 右、+y 上、−z 前）に合わせ、実機で確かめる
- 単体テストを書く（左22°・下8°・1.5m のときの値など）

### settings/HudLayoutConfig

- PLAN.md §2.1 と UI.md §3 の値をまとめる: 左クラスタ（左22°、下8°）、右クラスタ（右22°、下8°）、距離 1.5m、中央クリアゾーン ±15°、クラスタとクリアゾーンの間隔 1°以上、ミニマップの直径（歩行中 8°、停止中 12°）
- 文字サイズの倍率 `textScale`（後述）
- 今回は固定値でよい（設定画面やプリセットの切替は作らない）

### ui/hud/HudScene

- `@OptIn(ExperimentalFollowingSubspaceApi::class)` で `Subspace(follow = FollowTarget.view(FollowMode.tight(TrackedDimensions.All)))` を使う（UI.md §6 のヘッドロック）。T1 で確かめた API 名に合わせる
- 中に左クラスタ、右クラスタ（今回は仮の円と矢印。T5 で地図に置き換える）、角度ガイド（デバッグ時のみ）を置く
- 追従処理は `HudScene` の中に閉じ込める（PLAN.md §6 のリスク対策）
- 画面を消さない（`FLAG_KEEP_SCREEN_ON`）

### ui/hud/StatsPanel（左クラスタ。UI.md §3.1）

- 基準点は左22°・下8°。パネルの範囲は水平 左29°〜左16°、垂直 下2°〜下16° に収める。**右端が左16°より内側（中央寄り）に入らないこと**
- 中身は左揃えで上から: 距離（数字 32dp + 単位 16dp、太さ 500、`#E0F7FF`）、歩行時間（16dp）、補助行（14dp、`#9FE1CB`）、住所（14dp、`#B5D4F4`）
- 補助行が空のときは詰める
- 文字の見た目の大きさ: UI.md §3.1 の目安（距離の数字は視角 約3.5°、ほかは 約1.5°）になるよう、`HudLayoutConfig.textScale` で全体を同じ比率で拡大縮小する。初期値は、1.5m の距離で 32dp の数字が約3.5°になるように計算して決める（計算式を最終報告に書く）。UI.md §3.1 は「dp 値を実機を見て調整する」としているので、この調整は仕様の範囲内
- 表示文字列は T3 の `ui/format/` の関数を使う

### ui/theme/AdditiveDisplayTheme

- 背景は完全に透明（`Color.Transparent` / `#000000`）。パネルやカードに塗りを付けない。影や elevation も付けない
- 文字色は UI.md の色をそのまま使う

### 状態ごとの表示（UI.md §5 をそのまま実装する）

| | 歩行中 | 停止中 | 高速移動 | 測位なし |
|---|---|---|---|---|
| 左右クラスタ | 表示 | 表示 | **非表示** | 表示 |
| 不透明度 | 70% | 100% | — | 70% |
| 距離の色 | `#E0F7FF` | `#E0F7FF` | — | `#888780` |
| 補助行 | なし | `経過 21:05 ・ 15'05"/km`（ペースは100m 以上のときだけ） | — | `GPS 測位中…` |
| 住所 | 1行 | 2行 | — | 1行（前回の値を `#888780` で） |
| 右クラスタの直径 | 8° | 12° | — | 8° |
| 右クラスタの線と矢印 | 通常 | 通常 | — | 薄く（線30%、矢印 `#888780`） |
| その他 | | | 左クラスタの位置に `高速移動中のため非表示` とだけ出す（14dp、不透明度85%） | |

- 状態が変わるときは、不透明度と右クラスタの大きさを約300ms かけて滑らかに変える
- 住所が古い（stale）ときも `#888780` で出す

### 右クラスタ（仮）

- 基準点は右22°・下8°（円の中心）。円の枠は 1px 相当、`#E0F7FF` の不透明度40%。中心に上向きの矢印 `#7FDBFF`
- **左端が右16°より内側に入らないこと**（停止中の直径12°で 右16°〜右28°）

### ui/hud/DebugAngleGrid（UI.md §7。デバッグビルドのみ）

- 起動時の intent extra で表示する: `adb shell am start -n io.github.yamichi77.aurawalkinfo/.MainActivity --ez grid true`。**既定は非表示**（散歩もデバッグビルドで行うため）
- 正面 1.5m の平面に1枚の透明パネルとして描く。角度 θ の位置は、その平面上で d·tan(θ) にする（HudPlacement の球面計算とは別の計算にして、互いに確かめられるようにする）
- 描くもの: 表示範囲の枠（左右30°・上下20°、白の破線）、中央クリアゾーン（左15°〜右15°、琥珀色の破線と薄い塗り）、目線 0° の水平線、10° / 15° / 20° / 30° の目盛りと数字、左右が分かるように「L」「R」の文字
- 符号の確認は、下の「配置の自己診断」で自動で行う。自己診断で姿勢が取れなかったときだけ、予備として、左22°・下0°付近に yaw の符号を逆にした2枚の小さな確認用パネル（「A」「B」と書いた四角）を上下に並べて出し、ユーザーにどちらが正面を向いて見えるかを聞く

### 配置の自己診断（デバッグビルドのみ。ユーザーの目の代わり）

- `--ez selfcheck true` で起動すると、各パネル（左クラスタ、右クラスタ、角度ガイド）が配置された後に、**頭に対する実際の姿勢**を測って logcat（タグ `AuraPlacement`）に出す
  - パネルの姿勢は Compose for XR のレイアウト座標（`SubspaceModifier.onGloballyPositioned` / `onPlaced` などで取れる pose）か、scenecore の Entity の pose から取る。頭の姿勢は ARCore for Jetpack XR の `ArDevice`、または scenecore の頭の pose から取る。実際のライブラリにある API を使い、使った API を最終報告に書く
  - 出す値: 各パネルの中心の yaw・pitch（頭の正面を 0° とする、UI.md の「左」「右」「上」「下」の表記）、頭までの距離、中央寄りの端の yaw、パネルの法線と「パネル → 頭」の向きのなす角（正しく頭を向いていれば 0° 付近）
  - 判定して `PASS` / `FAIL` を出す: 左クラスタの中心が 左22°±1°・下8°±1°、左クラスタの右端が左16°より外側、右クラスタの左端が右16°より外側（直径12°のとき）、各パネルの法線のなす角が 5° 以内、距離が 1.5m±0.05m
  - `FAIL` なら、原因（符号、原点の位置、m → dp 換算）を数値から推定して HudPlacement を直し、`PASS` になるまで繰り返す。符号と原点について分かったことは `docs/device-capabilities.md` の「回転の符号」の欄に記録する
- 頭を動かしたときに追従しているかも、自己診断の中で、数秒おきに数回測って値が変わらないこと（ヘッドロック）で確かめる。測っている間に装着者が頭を動かすとは限らないので、「頭の pose 自体が変化したか」も一緒に出す

### 権限と起動

- 起動時に位置の権限（と、T2 で必要と分かった `HEAD_TRACKING`）を要求する。許可されたら `WalkSessionRepository.start()` で計測を始める。拒否されたら左クラスタに `位置の権限がありません` と出す
- 非 XR 端末では、T3 の値を 2D の画面にそのまま並べるだけでよい（`ui/fallback/PhoneScreen`）
- T2 のプローブ用モード（`--es mode probe` など）は残す

### 実機確認スクリプト（`scripts/device-check.sh`）

Git Bash で動くスクリプトを作る。オーケストレーターがユーザーの装着中に実行する。

- 前提の確認: adb で端末が見えるか、`dumpsys power` で装着中（表示がオン）か。非装着なら、その旨を出して終了コード 2 で止まる
- `JAVA_HOME` を JDK 21 にして `assembleDebug` → `adb install -r --no-streaming`
- 位置と `HEAD_TRACKING` の権限を `pm grant` で付与
- 順に実行: `--ez selfcheck true --ez grid true` → 各 `--es fakeState walking|stopped|fast|nofix` でキャプチャ。それぞれ `am start -S` で起動し直し、必要な秒数だけ待つ
- logcat から `AuraPlacement` などの結果を集めて `build/device-check/<日時>/log.txt` に保存し、PNG を同じフォルダに取り出す
- 最後に `PASS` / `FAIL` の一覧を標準出力にまとめ、`FAIL` が1つでもあれば終了コード 1
- 後続のタスク（T5、T6b）が項目を足しやすい形にする（項目ごとの関数など）

## 確認手順（すべて自動で行う）

1. `./gradlew test` と `./gradlew assembleDebug` が通る
2. **状態ごとの表示の単体テスト:** 状態から見た目の仕様（クラスタの表示・非表示、不透明度、距離の色、補助行の文字列、住所の行数と色、右クラスタの直径、線と矢印の薄さ）を返す純粋な関数（例: `hudVisualSpecFor(state)`）を作り、上の表のすべてのセルを単体テストで確かめる
3. **Compose UI テスト（Robolectric）:** 左クラスタの Composable を4つの状態で表示し、出ている文字列（`1.24` `km` `歩行 18:42` `経過 21:05 ・ 15'05"/km` `GPS 測位中…` `高速移動中のため非表示` と住所）と、高速移動のときに距離などが出ていないことを確かめる
4. 実機で、デバッグビルドだけの intent extra で状態を固定して起動できるようにする（`--es fakeState walking|stopped|fast|nofix`。距離・時間・住所はダミー値）。4つの状態と `--ez grid true` のそれぞれでクラッシュしないことを確かめる
5. T2 で作ったアプリ内キャプチャで、4つの状態の左クラスタと右クラスタ、角度ガイドの中身を PNG にして取り出し、自分で開いて、文字・色・透明な背景が仕様どおりかを確かめる。非装着だと空間 UI が出ないので、**パネルの中身の Composable は 2D の画面でも同じものを表示してキャプチャできるようにする**（例: 非装着時やスマホでは、左クラスタと右クラスタの中身を 2D の画面に並べる）。これで非装着でも中身は確かめられる
6. 装着中なら `scripts/device-check.sh` を実行して、配置の自己診断をすべて `PASS` にする。非装着なら、スクリプトが「非装着」で止まることまで確かめる
7. 最終報告の「ユーザーに確認してほしいこと」には、自動では確かめられないことだけを書く（例: 角度ガイドの枠がグラスの表示範囲の端とおおよそ一致するか、文字が読みやすいか）

## 受け入れ条件

- テストとビルドが通り、実機で `grid` と各 `fakeState` がクラッシュせずに表示される
- 実機の配置の自己診断がすべて `PASS`（左クラスタが 左22°・下8° 付近、中央クリアゾーンにかからない、パネルが頭を向いている）。回転の符号を `docs/device-capabilities.md` に記録した
- 状態ごとの表示が上の表どおりであることを、単体テスト・Compose UI テスト・アプリ内キャプチャで確かめた

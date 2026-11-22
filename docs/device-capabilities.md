# XREAL AURA 実機能力調査（T2）

- **調査日** 2026-11-22
- **端末** XREAL AURA Compute Puck（`ro.product.model` は `Android XR Puck`、Android 14、ビルド `UXP4.261012.001.O5`）
- **使用ライブラリ** Jetpack XR Runtime 1.1.0-alpha03、SceneCore 1.1.0-alpha02、Compose for XR 1.0.0-rc01、XR Material3 1.0.0-alpha17、ARCore for Jetpack XR 1.1.0-alpha02、Compose BOM 2026.02.01、Play services Location 21.4.0、Maps SDK 19.2.0、maps-compose 7.0.0
- **実機の状態** USB 接続・給電中、グラス非装着扱い（`mLastSleepReason=xr_doff`、表示 `OFF`、`mInteractive=false`）、有効な Maps API キーなし。ADB の `KEYCODE_WAKEUP` と一時的な USB 給電時のスリープ抑止でも表示は復帰しなかった。スリープ抑止の設定値は元の `0` に復元した。

## 位置・XR

| 項目 | 結果 | 確かめ方 | アプリへの影響 |
| --- | --- | --- | --- |
| Google Play services | `SUCCESS (0)`。26.45.31 (250400-1020418964)、versionCode 264531136 | `probe` で `isGooglePlayServicesAvailable()` とパッケージ情報を取得 | Fused Location の利用を確認 |
| 位置プロバイダ・GNSS | `passive`、`network`、`fused`、`gps` がすべて有効。GNSS は Broadcom BCM4778（2022年、GLL 166.20.25） | `getAllProviders()`、`isProviderEnabled()`、GNSS ハードウェア情報 | GPS プロバイダへのフォールバック候補あり |
| Geocoder | `Geocoder.isPresent() == true` | `probe` で確認 | 逆ジオコーダー実装あり。実際の住所取得は別途確認 |
| Fused の初回測位 | 室内で取得できた回は 0.03 秒と 0.05 秒。ともに精度 100.0 m、provider `fused`、速度・方位なし。30秒以内に新規測位がない回もあった | 正確な位置情報を許可し、高精度の位置更新を購読。購読開始以降の初回更新を記録 | Fused の受信経路は動作。100 m の測位は HUD の精度条件外 |
| GPS の初回測位 | 室内で測位できず（30秒以内の新規更新なし） | `LocationManager.GPS_PROVIDER` の更新を30秒間購読 | 屋外で初回測位時間、精度、速度・方位の有無を確認する |
| XR セッションと SPATIAL | `SessionCreateSuccess`、`SessionConfigureSuccess` | `HEAD_TRACKING` を付与し、`Session.create(activity)` と `Config.Builder.setDeviceTracking(DeviceTrackingMode.SPATIAL)` による設定結果を確認 | SPATIAL を設定できる。装着時の姿勢更新を再確認する |
| `ArDevice` の姿勢 | 10秒間で `STOPPED`、姿勢の変化なし。装着時は未確認（ユーザー確認待ち） | `ArDevice.state` の追跡状態と追跡中の姿勢の変化を監視 | T4 の頭部姿勢の判断は装着時の実測まで保留 |
| 空間 UI | `ui=false`、`3d=false`、`environment=false`、`passthrough=false`、`audio=true` | グラス非装着扱いで `LocalSpatialCapabilities` の全5項目を確認 | 装着後に再測定。地図の2Dフォールバックもこの状態で試行 |

測位結果として出力するのは初回までの秒数、精度、プロバイダ名、速度と方位の有無。ランタイム権限はアプリから要求できる。今回は `ACCESS_COARSE_LOCATION`、`ACCESS_FINE_LOCATION`、`HEAD_TRACKING` を `adb shell pm grant` で事前付与した。

## 地図

| 項目 | 結果 | 確かめ方 | アプリへの影響 |
| --- | --- | --- | --- |
| `map`（通常） | 2D で `GoogleMap ready`、クラッシュなし。15秒以内の `onMapLoaded` なし。スナップショット指標は未取得 | 有効なキーなしでデバッグモードを起動し、`AuraProbe` と起動後のプロセスを確認 | 空間パネル上の描画、ロゴとタイルは未確認（ユーザー確認待ち） |
| `maplite`（ライト） | 2D で `GoogleMap ready`、クラッシュなし。15秒以内の `onMapLoaded` なし。スナップショット指標は未取得 | 同じ条件で `GoogleMapOptions().liteMode(true)` を使用 | 通常・ライトの採用判断は保留 |
| 地図のピクセル診断 | 初回は両モードで SDK のスナップショットスレッドが `IllegalArgumentException: width and height must be > 0` でクラッシュ。修正後は `interactive=false, layout=0x0` を検出してスナップショットを省略 | `AndroidRuntime` を確認後、画面状態とレイアウト寸法をチェックして再実行 | 有効なキーを入れた APK で `onMapLoaded` までの秒数と、`snapshot()` の非黒画素率・平均輝度を採取する。タイルとロゴの装着時の見え方も確認する |

## 入力・撮影

| 項目 | 結果 | 確かめ方 | アプリへの影響 |
| --- | --- | --- | --- |
| タッチパッド候補 | `IQS9150`（今回の `event5`）。`INPUT_PROP_DIRECT`、`BTN_TOUCH` / `BTN_TOOL_*`、`ABS_MT_*`（位置、接触ID、圧力など）。Android でのクラスは `TOUCH \| TOUCH_MT \| TOUCHPAD`、ソースは `MOUSE \| TOUCHPAD`。別に方向キーなどを提供する仮想キーボード `uinput_nav` がある | `adb shell getevent -lp` と `adb shell dumpsys input` | ハードウェアの分類を確認。アプリに届くイベントは装着して実タップで確認する |
| アプリの入力ログ | `KEYCODE_A`、タップ、マウス移動の注入コマンドはいずれも成功。休止中の `AuraInput` に記録なし。実タップは未確認（ユーザー確認待ち） | `adb shell input keyevent`、`input tap`、`input mouse motionevent MOVE` と `adb logcat -s AuraInput` | パックでの実タップを10秒間試して受信イベントを確定するまで HUD 切替は割り当てない |
| システムのスクリーンショット | `KEYCODE_SYSRQ` 後に新規ファイルなし | `/sdcard/Pictures/Screenshots/` と `/sdcard/DCIM/Screenshots/` を確認 | 装着時はグラスの Home と X の同時押しでも撮影できる。[XREAL の操作ガイド](https://tutorials.xreal.com/docs/aura/using-aura/navigation/) |
| システムの画面録画 | `INVALID_LAYER_STACK`。有効な動画ができず、1コマ目は取得できなかった | `screenrecord --time-limit 3`、`adb pull`、FFmpeg | 装着中の録画可否は未確認 |
| `screencap` | 1920 × 1200、全画素が黒 | `adb exec-out screencap -p` で保存して開き、画素も確認 | この実機状態ではパネルを検証できない |
| アプリ内の PNG | `probe` の2D起動時フレームは 1536 × 960。見出し・空間 UI の状態・進行中の表示が透明背景に写った。診断完了後のフレームはタイムアウト。`map` / `maplite` の Compose PNG は 600 × 600、全画素が透明 | `--ez capture true`、`adb exec-out run-as … cat files/…`。取得物を `build/device-shots/` で開いて確認 | Compose 描画には再利用可能。地図面の検証は Maps SDK の `snapshot()` を使用。XR 上の配置と実際の見え方は装着して確認 |

### 再測定

グラスを装着してロック解除した状態で、PC から次を実行する。休止中に既存の Activity へ intent を届けるだけでは診断のログが出なかったため、`-S` で毎回起動し直す。PowerShell で PNG をリダイレクトする場合は、ネイティブ出力をバイト列のまま保存できる PowerShell 7.4 以降を使う。

```powershell
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
$pkg = "io.github.yamichi77.aurawalkinfo"
& $adb shell am start -S -n "$pkg/.MainActivity" --es mode probe --ez capture true
& $adb logcat -s AuraProbe AuraInput
```

- `probe` 起動後に頭をゆっくり動かし、`ArDevice` の追跡状態と姿勢の変化を確認する。パックのタッチパッドでポインターをパネル上へ動かし、数回タップして `AuraInput` を採取する。
- 今回の起動での `capture probe-panel.png` の保存成功ログを確認し、`adb exec-out run-as io.github.yamichi77.aurawalkinfo cat files/probe-panel.png` で PNG を取り出す。T4 以降でも、対象パネルの Modifier に `capturePanelOnNextDraw("名前.png")` を付けて再利用できる。
- `mode` を `map` と `maplite` に替えて通常・ライトを比較する。有効なキーを使う試験では `local.properties` にキーを設定した後の APK で実行し、ロゴ・道路・ラベルと `AuraProbe` を確認する。

## 回転の符号（T4）

| 軸 | 正方向の実測 |
| --- | --- |
| yaw | 未測定（装着待ち） |
| pitch | 未測定（装着待ち） |
| roll | 未測定（T4 の配置では roll を明示的に回転させていない） |

- T4 の確認時は `mWakefulness=Asleep`、`mLastSleepReason=xr_doff`。非装着中は PNG で 2D の描画内容を確認できた。グラス上の配置・原点・回転符号は装着時の実測待ち。
- 実装の初期値は +X が右、+Y が上、−Z が正面。パネルは局所 +Z が頭を向くよう回転する。Compose XR 1.0.0-rc01 の `ViewTarget` が前方へ 0.5 m ずらすことは同梱バイナリで確認し、配置で相殺した。
- グラスを装着してロックを解除し、Git Bash で `bash scripts/device-check.sh` を実行する。最初の約12秒は頭の向きをゆっくり少し変えてから止める。`AuraPlacement` が頭に対するパネルの yaw・pitch・距離・中央寄りの端・法線角を5回記録する。ログは `build/device-check/<日時>/log.txt` に保存される。非装着で PNG のみ取る場合は `--preview` を付ける。

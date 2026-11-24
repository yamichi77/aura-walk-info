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

- 屋内では fused の位置が約5〜6秒おき・精度 11〜12m だった（2026-11-23）。

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
- グラスを装着してロックを解除し、Git Bash で `bash scripts/device-check.sh --only placement,headmotion` を実行する。`AuraPlacement` は静止サンプルで配置を判定し、表示中のパネルの内側の端を `clearzone` の参考値として記録する。頭の回転による非表示を無効にしている間は `follow` をスキップする。ログは `build/device-check/<日時>/log.txt` に保存される。非装着で PNG のみ取る場合は `--preview` を付ける。

## 安全表示と入力（T6b）

- 非装着時は `mWakefulness=Asleep`、`mLastSleepReason=xr_doff`。通常の `device-check.sh` は終了コード 2 で停止した。2D プレビューでは最初のフレームを保存できたが、スリープ中の状態切替で描画が止まることがあった。プレビューの診断では状態切替を logcat で待ち、同じプロセスの Activity を再生成して PNG を保存した。
- 実機の 2D プレビューでは左パネルに注意文の全文が入り、約15秒で消えた。その後の起動では通常の数値表示に戻った。4倍速の合成ルートでは、再生時刻 37 秒の `WALKING -> FAST` から実時間 0.442 秒後に、左パネルに高速移動のメッセージだけを確認した。
- T6b の時点では、パックのタッチパッドからアプリに届く実イベントと、装着時の注意文の位置は未確認だった。装着時のタップは T8 の記録を参照。

## 装着時の追従と入力（T8）

- `FollowMode.tight` でも頭の回転中に HUD の追従が最大約5°遅れ、右クラスタの内側の端は一時的に右11.8°まで中央クリアゾーンに入った。静止時の揺れは ±1° 程度だった（`build/device-check/20261122-235905/log.txt` の `AuraPlacement`）。
- パックのタッチパッドのタップは Full Space の HUD でアプリに届かず、`AuraInput` にイベントは記録されなかった。システムのポインター操作になっていると推測されるが未確認。

## ハンドトラッキングのタップ（T9、2026-11-23）

- AURA は `android.hardware.xr.input.hand_tracking` を持つ。
- 手を伸ばしてつまむ操作は、HUD の SpatialPanel の中の Compose `Modifier.clickable` に届いた（`AuraInput: CLICK target=left|map|bigmap source=compose`）。左クラスタのタップで開始のカウントダウンとキャンセル、右下の地図のタップで大きい地図の開閉を確認した。
- パックのタッチパッドのタップは、引き続きアプリに届かない（T8 の記録）。操作はハンドトラッキングのタップを前提にする。

## 大きい地図の 3D（T16、受け入れで実行）

1. 有効な Maps SDK のキーを設定したデバッグビルドを使う。AURA を装着してロックを解除し、Git Bash で `bash scripts/device-check.sh --only map3d` を実行する。キーなしの場合は `SKIP` が出る。
2. 診断は `--es mode map3d` で起動し、大きい地図と同じ寸法の空間パネルに、傾きとズームの4条件（0° / 16.5、45° / 17、60° / 17.5、67.5° / 17.5）を約8秒ずつ表示する。
3. `build/device-check/<日時>/log.txt` の `AuraMap` で、要求・実測の傾きとズーム、北向き、読み込み秒数、円内の非黒画素率・平均輝度・最大輝度、現在地の投影位置と円の中心の差を確認する。`build/device-shots/<日時>/map3d-*.png` を開いて、地図と建物の描画を比較する。
4. 通常モードで停止中に大きい地図を開き、下端の `3D` をつまむ。暗い灰色の建物が立体に見え、矢印が円の中心に留まること、`2D` で元に戻ることを確認する。再度 3D を選んで中央付近をつまんで閉じ、開き直して 3D が選ばれていることを確認する。

| 条件（傾き / ズーム） | 結果 |
| --- | --- |
| 0° / 16.5 | 2026-11-24: 傾き・ズームとも要求どおり。読み込み 3.7 秒 |
| 45° / 17 | 2026-11-24: 傾きは要求どおり。8 秒以内に読み込み完了の通知なし（画像は描画済み） |
| 60° / 17.5 | 2026-11-24: 傾きは要求どおり。読み込み 2.7〜5.3 秒 |
| 67.5° / 17.5 | 2026-11-24: 傾きは要求どおり（SDK は 67.5° まで受け付けた） |

## 追加機能の確認（T15、受け入れで実行）

1. AURA を装着してロックを解除し、Git Bash で `bash scripts/device-check.sh --only heading,speech` を実行する。準備完了後、`heading` で正面を向いて約60秒間静止する。Geospatial を試すときは静止前に画面でデータの利用に同意する。スクリプトは `AuraProbe` を採取し、`heading result` と `speech result` を集計する。センサーと Geospatial の個別結果は `build/device-check/<日時>/log.txt` を見る。静止時は追跡の各状態の回数と、360° 境界をまたいで連続化した yaw の最大値と最小値の差（`rangeDeg`）を記録する。
2. `speech` が起動したら、マイクの許可を求められた場合は許可し、画面に指示が出たら「東京駅」と話す。認識の受付は最長8秒。端末内認識サービスが利用できない場合はシステム既定の認識サービスで試す。日本語モデルがインストール済みか、途中結果と最終結果が届いたか、最終結果が「東京駅」か、Geocoder の件数を確認する。
3. 通常モードで歩行記録を開始し、屋外で5〜10分、向きの異なる直線区間を含むコースを歩く。歩行記録を終了したら、[フィールドログの手順](field-test.md) で CSV を取り出し、`python scripts/heading-analysis.py <CSV>` を実行する。分析は1秒ごとの記録を使うため、瞬間的な追跡断や軌跡の曲がりを見落とすことがある。
4. 軸と符号は安全な場所で確認する。歩行記録を動かしたまま、頭を水平にしてその場で右を向き、前後の `ar_yaw_deg` の最短角度差を比較する。増えれば `--yaw-sign=1`、減れば `--yaw-sign=-1`。また、`ar_yaw_deg` が 0° 付近になる向きで前進して、主に減ったのが Z なら `--forward=-Z`、増えたのが Z なら `--forward=+Z` を加えて分析する。歩行のばらつきだけでは、正面を180°入れ替えた候補と yaw 符号の候補に同率が残る。

Geospatial の設定確認には ARCore API が有効な Google Cloud プロジェクトと承認設定が必要。キーなしで試す場合はアプリのパッケージ名と署名証明書の SHA-1 に対応する Android OAuth クライアントを設定する。[Android Developers の設定手順](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/geospatial/setup)

| 項目 | 受け入れ結果 |
| --- | --- |
| 地磁気・回転ベクトル | 2026-11-24: パックに回転ベクトル（type 11）と地磁気の回転ベクトル（type 20）がある。地磁気センサー単体（type 2）は一覧に無い。いずれもパックの向きで、頭の向きではない |
| Geospatial の設定・追跡状態 | 2026-11-24: API はある（available=1）。ARCore API の認証が無いため追跡は始まらない（running=0） |
| 静止60秒の追跡と yaw の範囲 | 2026-11-24: 追跡は 60 秒とも TRACKING。静止していた 14 秒間の yaw の変化は 0.3° 以内（1 秒ごとの記録 `heading yawTrace`）。範囲が大きく出た回は、測定中に頭を動かしていた |
| 端末内の日本語認識・認識結果・Geocoder 件数 | 2026-11-24: 端末内の認識（`createOnDeviceSpeechRecognizer`）は日本語モデルが無く、聞き取りが始まらない。既定の認識サービスは Google の音声サービス（`com.google.android.tts`）で、アプリ本体の音声入力ではこちらで日本語を認識できた。Geocoder は 1 件 |
| 歩行ログの標本数・オフセット・ばらつき・ドリフト | |
| ARCore 座標の正面と yaw の符号 | |

## 受け入れ（T20、2026-11-24）のその他の結果

- **adb の接続:** USB の接続が数秒おきに切れてはつながり直す。`adb install`（ストリーミング）は途中で失敗した。`adb push` してから端末内で `pm install` すると完了する。Git Bash では `MSYS_NO_PATHCONV=1` を付けないと `/data/local/tmp/...` が Windows のパスに書き換わる
- **配置（placement）:** PASS。左パネルは時刻の行の分だけ上に伸び、中心は下7.0°。静止時の内側の端は左 15.5°・右 15.1° 以上。頭を速く回している間（100°/秒以上）は、追従の遅れで内側の端が中央クリアゾーンに入る（参考値。以前からの挙動）
- **BLE:** 20 秒のスキャンで 2,472 件・56 台を受信。高度化PICS（`0x01CE`）は周りに無かった
- **信号の表示（合成）:** 赤 → 青点滅 → 青、受信が途切れたときの灰色と消える動き、初回の「信号は参考表示です」を確認（PASS）
- **ナビ（合成）:** 曲がり角・残り・到着の表示を確認（PASS）。実際の施設検索と経路（Places API (New)、Routes API）でも案内が始まった
- **空の方位帯（合成）:** 印の角度と、上14°〜19° の帯への収まりを確認。頭を正面に向けたままにする前提の確認スクリプトでは、測定中に頭が動いて FAIL になった回がある（表示の不具合ではない）
- **3D の大きい地図:** 建物を黒で塗って輪郭だけにする案は、立体の建物に輪郭の指定が効かず真っ黒になった。`#262626` の塗りにした（市街地の地面も同じ灰色になる）。お店は消して駅だけにした

## 2回目の受け入れ（T20 §7、2026-11-24）の室内の結果

- **adb:** USB の接続が切れ続けたので、ワイヤレスデバッグに切り替えて安定した（`adb mdns services` で見える）。確認スクリプトの `adb_retry` は5回まで再試行するようにした
- **シーンの認識の権限:** AURA にある名前は `android.permission.SCENE_UNDERSTANDING_COARSE`。`androidx.xr.runtime.manifest.SCENE_UNDERSTANDING_COARSE` は存在せず、付与も要求もできなかった（直した）
- **空間に固定した表示（`--es mode spatialtest`）:** アンカー（`AnchorSpace`）に置いた表示も、`ActivitySpace` に直接置いた表示も、`MeshEntity` と `PanelEntity` の両方が見えた。4m・10m・60m で見えた（見かけの大きさを同じにして確かめた）。システムのログに `Tried to read anchor with uuid ... but was unable to find it` が出るが、表示には影響しなかった
- **空間の印（`--only spatial`）:** 初回は何も見えなかった。原因は、曲がり角の矢印の線が細すぎた（39m 先で視角 0.05°）ことと、合成の経路では矢印と光の柱が視界の端（左右 27°）に出ていたこと。線を視角 0.3° 以上、矢印を 5° 以上にして、3つとも見えた（ユーザー了承）。信号の柱（25m 先、正面）は今のままでよい
- **VPS の利用の確認:** 合成のナビを始めると、左クラスタの位置に確認と丸いボタンが出て、`使う` で保存された。室内なので VPS は待機のまま

## 屋外の歩行（ユーザーの試用、2026-11-24）

フィールドログ（8分・約730m、緯度経度なし）の分析の結果。

- **頭の位置は追えていない:** ARCore の頭の姿勢の位置（`ar_x_m`、`ar_z_m`）は、730m 歩いても ±10cm の範囲から動かなかった。回転（yaw）は追えていて、追跡の状態は最後まで TRACKING。AURA がこのアプリに渡す姿勢は、回転と首の動き程度（3DoF 相当）と考えられる
- **そのため T15 の北の向きの推定は働かなかった:** 「ARCore の位置の移動の向き」と GPS の進行方向を突き合わせる方式なので、標本が1つもできなかった（`north_offset_deg` は最後まで空欄）。北の向きがないので、空の方位帯・頭の向きを基準にした矢印・空間の印は出なかった
- **GPS:** fused、位置の精度は中央値 7.5m。報告される進行方向の精度は中央値 24.5°（25° と 45° が多い）
- **代わりの方式の試算:** 歩いている間（速度 1.0m/s 以上、進行方向の精度 30° 以内）の「GPS の進行方向 − 頭の yaw の向き」は、外れ値（30° 超）を除いて 346/394 件、平均 154.6°、ばらつき 10° にまとまった。1分ごとの平均は 140〜168° で、一方向へのずれ（ドリフト）は見られなかった。yaw の符号は `HeadingAxes.YAW_TO_CLOCKWISE = -1` と合う
- **VPS:** その場所で使えるかの問い合わせが時間切れ（`availability=TIMEOUT`）になり、使われなかった
- **API の利用回数（この日）:** Places 4 回、Routes 11 回

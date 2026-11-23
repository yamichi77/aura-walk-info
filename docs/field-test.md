# フィールドログの取り出し

デバッグビルドの実測セッションは、端末のアプリ専用領域 `files/fieldlog/` に CSV を保存する。Git Bash でファイル名を一覧し、必要なセッションだけ取り出す。

```bash
adb exec-out run-as io.github.yamichi77.aurawalkinfo ls files/fieldlog
mkdir -p fieldlogs
name='<一覧に表示されたCSVのファイル名>'
adb exec-out run-as io.github.yamichi77.aurawalkinfo cat "files/fieldlog/$name" > "fieldlogs/$name"
```

PowerShell 7.4 未満では外部コマンドの出力を `>` で保存する際にバイト列が変わることがある。上の手順は Git Bash で実行する。`fieldlogs/` は Git の管理対象外。CSV は UTC のセッション開始日時で命名し、10行ごととセッションの切替・終了時に書き出す。

1行は直前の記録以降の1秒分。精度と採否は区間内の最後の測位、加算距離は区間内の合計。測位が来なければ精度は空欄、採否は `NO_SAMPLE`。初回の基準点は `ACCEPTED` で加算距離はゼロ。緯度経度と住所は列に含めない。

| 列 | 内容 |
|---|---|
| `elapsed_s` | セッション開始からの秒数 |
| `state` | 区間末の状態 |
| `avg_speed_mps` | 3秒窓の平均速度（m/s）。値がなければ空欄 |
| `accuracy_m` | 区間内の最後の測位の精度（m） |
| `added_distance_m` | 区間中に加算した距離（m） |
| `fix_decision` | `ACCEPTED`、`POOR_ACCURACY`（精度不足）、`TOO_LITTLE_MOVEMENT`（移動量不足）、`NOT_WALKING`（歩行中でない）、`NO_SAMPLE` |
| `provider` | アプリが選んだ測位プロバイダ（`fused` / `gps`） |
| `ar_tracking` | 区間末の頭部追跡状態（`TRACKING` / `PAUSED` / `STOPPED`）。追跡の入力がなければ空欄 |
| `ar_x_m`、`ar_z_m` | 区間末の頭部の相対位置（m）。XR の姿勢観測開始時に最初に取得できた追跡位置が原点。追跡中の値がなければ空欄 |
| `ar_yaw_deg` | 区間末の頭部の yaw（度、ARCore の座標系）。水平成分が求まらなければ空欄 |
| `gps_bearing_deg`、`gps_bearing_acc_deg`、`gps_speed_mps` | 区間内の最後の測位の進行方位（北 0°、時計回り）、方位精度（度）、速度（m/s）。測位または個別の値がなければ空欄 |
| `north_offset_deg`、`north_uncertainty_deg` | 区間末の北オフセットと推定の不確かさ（度）。推定がなければ空欄 |

## 合成ルートの実機確認

T4 とマージしてデバッグ APK をビルドした後、オーケストレーターが Git Bash で `bash scripts/replay-check.sh` を実行する。任意の APK を使う場合は第1引数に指定する。結果は `build/device-check/<日時>/replay.txt`、各シナリオの診断行と取り出した CSV は同じディレクトリに保存する。

個別に再生するには、起動済みのデバッグアプリに以下を送る。`fast`、`walk_stop`、`nofix` を指定できる。`speed` の既定は1倍で、正の値から最大1,000倍まで指定できる。

```bash
adb shell am broadcast -a io.github.yamichi77.aurawalkinfo.REPLAY -p io.github.yamichi77.aurawalkinfo --es scenario fast --ef speed 4
adb logcat -s AuraSession
adb shell am broadcast -a io.github.yamichi77.aurawalkinfo.REPLAY -p io.github.yamichi77.aurawalkinfo --es scenario stop
```

再生開始と実測への復帰で、新しいセッションを開始する。再生中の CSV 記録は停止する。合成ルート終了後は、`stop` または次のシナリオの指示まで位置の入力が途切れた状態になる。

### 切替秒数の確認

速度の判定は、3秒窓の平均速度が閾値を越えてから5秒間を数える。JVM テストと実機スクリプトでは、高速移動への切替・高速移動からの復帰・停止への切替が、合成ルートの速度が変わってから5〜9秒後に起きることを確認する。測位なしへの切替は、最後の測位（29秒時点）から9〜11秒後に起きることを確認する。

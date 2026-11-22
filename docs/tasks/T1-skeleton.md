# T1: プロジェクトの骨組みと「Hello」パネル

まず [README.md](README.md) の共通ルールを読むこと。

## 目的

Android プロジェクトの骨組みを作り、XREAL AURA の Full Space に「Hello」パネルを1枚出す。
後続のタスク（T2〜T6）が Gradle ファイルを触らずに済むよう、必要になる依存関係もここで入れておく。

## 作るもの

### Gradle

- 単一モジュール `:app`。Kotlin DSL（`*.gradle.kts`）
- Gradle Wrapper を入れる（`gradlew`、`gradlew.bat`、`gradle/wrapper/*`）。ローカルにキャッシュがある Gradle 9.2.1 を優先する
- 依存バージョンは `gradle/libs.versions.toml` で固定する（Version Catalog）
- AGP はローカルにキャッシュがある 9.0.1 を優先する。Kotlin と Compose Compiler のプラグインは AGP と整合するものを選ぶ
- `applicationId` と `namespace` は `io.github.yamichi77.aurawalkinfo`
- `minSdk 34`、`compileSdk` と `targetSdk` は 36（36.1 で問題がなければそれでもよい）
- JVM ターゲットは 17 以上

### 依存関係（すべて `libs.versions.toml` 経由）

- Jetpack XR: PLAN.md §1.2 の5つ（`androidx.xr.runtime:runtime`、`androidx.xr.scenecore:scenecore`、`androidx.xr.compose:compose`、`androidx.xr.compose.material3:material3`、`androidx.xr.arcore:arcore`）
  - バージョンは Google Maven の最新を確認して決める（例: `https://dl.google.com/android/maven2/androidx/xr/compose/compose/maven-metadata.xml`）
  - **T4 で `Subspace(follow = FollowTarget.view(FollowMode.tight(...)))` と `@OptIn(ExperimentalFollowingSubspaceApi::class)` を使う。** 選んだ `androidx.xr.compose` のバージョンにこれらの API が存在することを、依存の jar/aar のクラス一覧などで確かめる。無ければ、存在する最も新しいバージョンにする
  - PLAN.md の注意: `scenecore 1.0.0-rc01` にはメモリリークがある。依存解決後の scenecore のバージョンを `./gradlew :app:dependencies` などで確認し、最終報告に書く
- Compose: BOM、`ui`、`foundation`、`material3`、`activity-compose`、`lifecycle-runtime-compose`、`lifecycle-viewmodel-compose`
- 位置と地図: `com.google.android.gms:play-services-location`、`com.google.maps.android:maps-compose`（と、それが要求する `play-services-maps`）
- コルーチン: `kotlinx-coroutines-android`
- テスト: `junit:junit:4.13.2`、`kotlinx-coroutines-test`
- secrets-gradle-plugin（`com.google.android.libraries.mapsplatform.secrets-gradle-plugin`）

### API キーの注入

- secrets-gradle-plugin で `local.properties` の `MAPS_API_KEY` をマニフェストに注入する
- `local.defaults.properties` を作り、`MAPS_API_KEY=DEFAULT_API_KEY` のようなダミー値を入れる（これはコミットする）
- `local.properties` が無い、または `MAPS_API_KEY` が無いときもビルドが通ること

### マニフェスト（PLAN.md Phase 0）

- `<uses-feature android:name="android.software.xr.api.spatial" android:required="false" />`
- アクティビティに `android.window.PROPERTY_XR_ACTIVITY_START_MODE` = `XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED`
- 権限: `ACCESS_FINE_LOCATION`（API 31+ の作法どおり `ACCESS_COARSE_LOCATION` も併記）、`android.permission.HEAD_TRACKING`、`INTERNET`
- `com.google.android.geo.API_KEY` の meta-data に `${MAPS_API_KEY}`
- テーマは暗色（XR グラスでは常にダークテーマ）

### コード

- パッケージのルートは `io.github.yamichi77.aurawalkinfo`。PLAN.md §4 のディレクトリ構成（`session/`、`location/`、`safety/`、`settings/`、`diagnostics/`、`ui/theme/`、`ui/hud/`、`ui/fallback/`）はこのルートの下に作る。今回は中身が無いディレクトリは作らなくてよい
- `MainActivity`（`ComponentActivity` + `setContent`）
  - `LocalSpatialCapabilities` で空間 UI が使えるか判定する
  - 使えるとき: `Subspace { SpatialPanel(...) { ... } }` で「Hello」と書いたパネルを1枚出す。背景は透明（黒）、文字は白か `#E0F7FF`
  - 使えないとき（普通のスマホ）: 2D の画面に「Hello」と出す
- `AppContainer`（手動 DI の入れ物。今は空でよい）

### その他

- `.gitignore` は既存のものを壊さない。必要なら追記する
- 実験的 API を使うところには `@OptIn` を明記する

## 確認手順

1. `JAVA_HOME` を JDK 21 にして `./gradlew assembleDebug` が通る
2. `./gradlew test` が通る（テストが0件でもよい。タスクとして成功すること）
3. `adb install -r app/build/outputs/apk/debug/app-debug.apk` で AURA に入る
4. `adb shell am start -n io.github.yamichi77.aurawalkinfo/.MainActivity` で起動し、`adb shell dumpsys activity activities` などで resumed になっていることを確認する。クラッシュしていないか `adb logcat` も見る
5. 可能なら `adb exec-out screencap -p` で画面を撮る（XR では撮れないこともある。撮れなければそう書く）
6. デバッグ署名の SHA-1 を取得する（`./gradlew :app:signingReport` か、`keytool -list -v -keystore %USERPROFILE%\.android\debug.keystore -alias androiddebugkey -storepass android`）。**最終報告にパッケージ名と SHA-1 を書く**（リポジトリには書かない）

## 受け入れ条件

- `assembleDebug` が通る
- AURA にインストールでき、起動してもクラッシュしない（パネルが見えるかはユーザーがグラスで確認する）
- デバッグ署名の SHA-1 とパッケージ名が最終報告にある
- 選んだ依存バージョン（特に androidx.xr.* と、解決後の scenecore）と、`FollowTarget` / `FollowMode` / `ExperimentalFollowingSubspaceApi` が使えることを確認した結果が最終報告にある

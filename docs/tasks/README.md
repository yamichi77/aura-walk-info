# タスク指示書（Sol 向け）

オーケストレーター（Opus）が書き、実装担当の Sol（Codex CLI）が実行する。1ファイル = 1タスク。

## すべてのタスクに共通するルール

### 最初に読むもの

- [AGENTS.md](../../AGENTS.md): 守るべきルール（必読）
- [PLAN.md](../PLAN.md): 計画全体。§2（安全設計）は特に重要
- [UI.md](../UI.md): 承認済みの UI 仕様。参照モックは [ui-mockup.html](../ui-mockup.html)
- [HANDOFF.md](../HANDOFF.md): 今回の目標（2026-11-23 午前の散歩で実機テスト）とタスク一覧

### やってはいけないこと

- PLAN.md §2 と UI.md の配置・閾値・状態ごとの表示を変えない。仕様に迷ったら、勝手に決めずに最終報告で「未決」として書く
- `git commit` と `git push` をしない。コミットはオーケストレーターがレビュー後に行う
- `local.properties` に `MAPS_API_KEY` を書かない（ユーザー自身が書く）。`sdk.dir` だけは書いてよい
- API キー、署名鍵、実在の位置ログ（座標・住所・GPX）をリポジトリに置かない。テストの位置データは合成データにする
- ドキュメントに書かれていない挙動（回転の符号、入力イベントの種類など）を推測で決めない。実機で確かめて `docs/device-capabilities.md` に記録する（座標や住所は書かない）

### この PC の環境

- PATH 上の `java` は Java 8。Gradle を使うときは必ず `JAVA_HOME` を Android Studio 同梱の JDK 21 に向ける
  - `C:\Program Files\Android\Android Studio\jbr`
  - Git Bash の例: `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`
  - PowerShell の例: `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'`
- Android SDK: `%LOCALAPPDATA%\Android\Sdk`（platforms: android-33 / 35 / 36 / 36.1、build-tools 〜36.0.0）
- adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- 実機 XREAL AURA（コンピュートパック）は USB で接続済み。`adb devices` で `model:Android_XR_Puck` として見える
  - ユーザーは別の部屋にいることがある。グラスをかけて目で確かめる必要があることは、最終報告の「ユーザーに確認してほしいこと」に書く
- Gradle の キャッシュ: Gradle 9.2.1 のディストリビューション、AGP 9.0.1、`androidx.xr.*` の一部（runtime/scenecore/arcore 1.0.0、compose 1.0.0-rc01）がローカルにある

### 確認は自動で行う（ユーザーの手を借りない）

ユーザーの方針: **なるべくすべてを内部テストで終わらせる。** ユーザーにグラスで見てもらう・触ってもらうのは、物理的にそれしか方法が無いことだけにする。

- 純粋なロジックは JVM の単体テスト（`./gradlew test`）
- Compose の表示（文字列、色、不透明度、表示・非表示）は Robolectric + Compose UI テスト（`createComposeRule`）で確かめる。依存は T3 で入れる
- 実機での確認は adb で行う
  - `adb install -r`、`adb shell am start`、`adb logcat`、`adb shell dumpsys`、`adb shell uiautomator dump`
  - 画面は `adb exec-out screencap -p > build/device-shots/<名前>.png` で撮る（XR で撮れるかは T2 で確かめる）。**撮った画像はリポジトリの `build/device-shots/` に置く**（`build/` は .gitignore 済み。地図や位置が写るのでコミットしない）
  - 撮れた画像は自分で開いて中身を確かめる
- 実機でしか分からないこと（パネルの実際の姿勢、地図が描画されたか など）は、デバッグビルドに自己診断を入れ、`PASS` / `FAIL` と数値を logcat に出して、adb で読み取る
- 屋外を歩かないと試せない挙動は、デバッグビルドの「合成ルートの再生」（T6 で作る）で実機上で再現する
- それでもユーザーにしか確かめられないこと（グラス越しの見え方、タッチパッドを触る、屋外の GPS）は、最終報告の「ユーザーに確認してほしいこと」に、手順と所要時間つきで書く

### 最終報告の形式（最後のメッセージに必ず含める）

1. やったこと（変更・追加したファイルの一覧）
2. 実行したコマンドと結果（`assembleDebug`、`test`、`adb install` など。失敗したものも書く）
3. 受け入れ条件ごとの達成状況
4. 指示書から外れた判断、推測で決めたこと、未決事項
5. ユーザーに確認してほしいこと（グラスをかけて見る必要があることなど）

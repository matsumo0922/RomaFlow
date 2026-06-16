# RomaFlow IME

RomaFlow IME は、ローマ字入力を続けながら、入力中の文字列を自然な日本語へリアルタイムに変換する実験的な日本語入力メソッドです。

このプロジェクトは `matsumo0922/kmp-template` をベースにした Kotlin Multiplatform コードベースです。

- アプリ名: RomaFlow
- Android アプリケーション ID: `me.matsumo.romaflow`
- Kotlin パッケージ名: `me.matsumo.romaflow`
- macOS を先行対象とし、InputMethodKit ホストから共通 Kotlin IME ロジックを呼び出します
- Xcode プロジェクトは XcodeGen で生成します
- Apple ホスト向けの Kotlin Multiplatform 出力には Swift Export を使用します
- Android InputMethodService 対応は、共有ライブ変換コアを macOS で検証したあとに予定しています

## 参考資料

- [日本語入力を作るときに必要だった本](https://mzp.booth.pm/items/809262) - 著者: SKK
- [inside-input-method 読解ガイド](docs/ref/inside-input-method-reading-guide.md)

## 検証

```sh
make detekt
./gradlew :androidApp:assembleDebug
```

## 依存セットアップ（暫定）

romaji→kana 変換には [WanaKana](https://github.com/WaniKani/WanaKana) の Kotlin Multiplatform port を使用します。upstream の `GreatTusk/wanakana-kmp` は macosArm64 target を宣言していないため、`:core:ime`（macosArm64）からそのままでは解決できません。macosArm64 target を追加する PR ([GreatTusk/wanakana-kmp#1](https://github.com/GreatTusk/wanakana-kmp/pull/1)) を出しており、マージ・リリースされるまでは fork を Maven Local に置いて利用します。

ビルド前に一度だけ実行してください（兄弟ディレクトリ `../wanakana-kmp` に clone され、Maven Local へ publish されます）。

```sh
make setup-wanakana
```

upstream PR がマージ・リリースされたら、`gradle/libs.versions.toml` の `wanakana` バージョンを正式版へ更新し、`settings.gradle.kts` の `mavenLocal()`・`make setup-wanakana`・fork clone を撤去できます。

## macOS ブートストラップ確認

```sh
xcodegen --version
make generate
./gradlew :core:ime:tasks --all
xcodebuild -project macosApp/RomaFlowMacOS.xcodeproj -scheme RomaFlowHarness -configuration Debug -destination 'platform=macOS,arch=arm64' build CODE_SIGNING_ALLOWED=NO
xcodebuild -project macosApp/RomaFlowMacOS.xcodeproj -scheme RomaFlowInputMethod -configuration Debug -destination 'platform=macOS,arch=arm64' build CODE_SIGNING_ALLOWED=NO
```

## Android Studio での macOS 実行メモ

macOS ターゲットには Xcode 26.5 を使用してください。Swift Export の成果物はコンパイラーバージョンの影響を受けやすく、Xcode 16.4 / Swift 6.1.2 では現在の Kotlin 2.4 Swift Export 出力をリンクできません。

Android Studio で Swift モジュールのバージョン不一致が報告された場合は、Gradle デーモンを停止し、Android Studio の `RomaFlowMacOS` DerivedData をクリーンしてから、Xcode プロジェクトを再生成して再実行してください。

```sh
./gradlew --stop
make generate
```

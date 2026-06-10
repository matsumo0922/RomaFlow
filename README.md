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

## 検証

```sh
make detekt
./gradlew :androidApp:assembleDebug
```

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

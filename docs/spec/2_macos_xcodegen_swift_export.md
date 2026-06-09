# macOS XcodeGen / Swift Export 進行メモ

## 現状

RomaFlow は macOS first で進める。現在のリポジトリは KMP / Compose テンプレート直後の状態で、Android launcher app、iOS Compose entry、設定、課金、広告などのテンプレート資産が残っている。一方で、macOS target、InputMethodKit host、IME 固有の shared core はまだない。

ローカル環境では XcodeGen が入っており、Xcode 26.5 が使える。Gradle の `:shared` module には `embedSwiftExportForXcode` task が出ているため、Swift Export を Xcode build phase から呼ぶ導線は使える。

## 方針

最初の目的は、macOS の IME shell と shared core の接続を確認すること。AI provider の品質や Android software keyboard UI はまだ扱わない。

KMP の export 境界は、既存の `:shared` ではなく IME 用の小さい module に寄せる。現在の `:shared` は Compose UI、設定、課金、広告、resource 依存を含むアプリ umbrella module なので、InputMethodKit から呼ぶ変換エンジンとしては大きすぎる。まずは `:core:ime` のような純 Kotlin module を作り、Swift から扱いやすい API だけを公開する。

Swift Export は Alpha なので、公開 API は保守的にする。Swift 側へ出す型は `InputBuffer`、`ConversionDraft`、`Segment`、`Candidate`、`RomaFlowEngine` などに絞り、generic-heavy な API や platform UI 依存を避ける。最初は同期的な fake provider で、key event、preedit 更新、candidate 表示、commit、segment lock の流れを検証する。

## 推奨ステップ

1. `:core:ime` を追加する。
   - `InputBuffer`
   - `ConversionDraft`
   - `Segment`
   - `Candidate`
   - `SegmentLockPolicy`
   - `FakeConversionProvider`
   - `RomaFlowEngine`

2. `:core:ime` を Swift Export できるようにする。
   - `macosArm64` を最初の target にする。
   - Intel Mac 対応が必要になったら `macosX64` を足す。
   - Swift module name は `RomaFlowImeCore` のように、Swift 側で読みやすい名前にする。

3. `macosApp/` を追加する。
   - `project.yml` を commit し、`.xcodeproj` は生成物として扱う。
   - Xcode target はまず InputMethodKit の `.inputmethod` bundle を目標にする。
   - 必要なら先に通常の macOS debug harness app を作り、Swift から KMP core を呼べるかだけ確認する。

4. XcodeGen の build phase から Swift Export を呼ぶ。
   - Run Script で `./gradlew :core:ime:embedSwiftExportForXcode` を実行する。
   - Xcode 側では生成された Swift module を import して、最小の smoke call を置く。

5. InputMethodKit host を薄く作る。
   - `IMKServer`
   - `IMKInputController`
   - composing buffer
   - preedit update
   - candidate window
   - commit

6. fake provider で IME としての状態管理を固める。
   - 入力バッファ更新
   - live conversion draft 更新
   - segment 選択
   - candidate 選択
   - locked segment の保持
   - commit 後の buffer clear

## XcodeGen で管理するもの

`macosApp/project.yml` に macOS target、Info.plist、build settings、Run Script を記述する。XcodeGen は target の `platform: macOS`、`type`、`sources`、`info`、`preBuildScripts` / `postCompileScripts` などを project spec で管理できる。

InputMethodKit の bundle では、Info.plist に少なくとも次の IME 固有 key が必要になる。

- `LSBackgroundOnly`
- `InputMethodConnectionName`
- `InputMethodServerControllerClass`
- `tsInputMethodIconFileKey`
- `tsInputMethodCharacterRepertoireKey`

## 参照

- Kotlin Swift Export: https://kotlinlang.org/docs/native-swift-export.html
- XcodeGen Project Spec: https://yonaskolb.github.io/XcodeGen/Docs/ProjectSpec.html
- Apple InputMethodKit: https://developer.apple.com/documentation/InputMethodKit
- Apple IMKServer: https://developer.apple.com/documentation/inputmethodkit/imkserver

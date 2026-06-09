# RomaFlow IME

RomaFlow IME is an experimental Japanese input method that lets users keep typing in romaji while the composing text is live-converted into natural Japanese.

The project is a Kotlin Multiplatform codebase based on `matsumo0922/kmp-template`.

- App name: RomaFlow
- Android application ID: `me.matsumo.romaflow`
- Kotlin package name: `me.matsumo.romaflow`
- macOS first, with an InputMethodKit host calling shared Kotlin IME logic
- Xcode projects are generated with XcodeGen
- Kotlin Multiplatform output for Apple hosts uses Swift Export
- Android InputMethodService support is planned after the shared live-conversion core is validated on macOS

## Verification

```sh
make detekt
./gradlew :core:ime:allTests
./gradlew :androidApp:assembleDebug
```

## macOS Bootstrap Checks

```sh
xcodegen --version
make generate
./gradlew :core:ime:tasks --all
xcodebuild -project macosApp/RomaFlowMacOS.xcodeproj -scheme RomaFlowHarness -configuration Debug -destination 'platform=macOS,arch=arm64' build CODE_SIGNING_ALLOWED=NO
xcodebuild -project macosApp/RomaFlowMacOS.xcodeproj -scheme RomaFlowInputMethod -configuration Debug -destination 'platform=macOS,arch=arm64' build CODE_SIGNING_ALLOWED=NO
```

## macOS Minimal Input Check

```sh
DERIVED_DATA_DIR=/tmp/RomaFlowDerivedData
make generate
xcodebuild -project macosApp/RomaFlowMacOS.xcodeproj -scheme RomaFlowInputMethod -configuration Debug -destination 'platform=macOS,arch=arm64' -derivedDataPath "$DERIVED_DATA_DIR" build CODE_SIGNING_ALLOWED=NO
xcodebuild -project macosApp/RomaFlowMacOS.xcodeproj -scheme RomaFlowHarness -configuration Debug -destination 'platform=macOS,arch=arm64' -derivedDataPath "$DERIVED_DATA_DIR" build CODE_SIGNING_ALLOWED=NO
make install-inputmethod DERIVED_DATA_DIR="$DERIVED_DATA_DIR"
open "$DERIVED_DATA_DIR/Build/Products/Debug/RomaFlowHarness.app"
```

Enable RomaFlow from System Settings, select it from the input menu, and type in the harness text editor. Lowercase `a`-`z` should appear as marked text, Space / Return should commit it, Delete should remove one composing character, and Escape should cancel the composition.

## Android Studio macOS Run Notes

Use Xcode 26.5 for the macOS targets. Swift Export artifacts are compiler-version sensitive, and Xcode 16.4 / Swift 6.1.2 does not link the current Kotlin 2.4 Swift Export output.

If Android Studio reports a Swift module version mismatch, stop Gradle daemons, clean Android Studio's `RomaFlowMacOS` DerivedData, regenerate the Xcode project, and run again.

```sh
./gradlew --stop
make generate
```

After building `RomaFlowInputMethod` from Android Studio, install the latest built input method and run `RomaFlowHarness`.

```sh
make install-inputmethod
```

`RomaFlowInputMethod` is installed as `~/Library/Input Methods/RomaFlow.app`.
The install target signs the app bundle and registers it with macOS Text Input Sources.
